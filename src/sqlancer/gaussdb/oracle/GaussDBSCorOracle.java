package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.SQLConnection;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.gaussdb.GaussDBErrors;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;

/**
 * SCor (SIGMOD '26) Short-Circuit Logic & Optimization Omissions Oracle for GaussDB.
 *
 * <p>
 * Standard SQL mandates short-circuit semantics for boolean connectors (AND, OR, CASE) to guard against
 * unsafe operations (e.g. division-by-zero, invalid string casts, negative logarithms).
 * SCor synthesizes guarded hazardous expressions and tests whether optimizer predicate pushdown or reordering
 * violates short-circuit semantics:
 * 1. AND_SHORT_CIRCUIT: (guard AND hazard) &equiv; CASE WHEN guard THEN hazard ELSE FALSE END
 * 2. OR_SHORT_CIRCUIT: (guard OR hazard) &equiv; CASE WHEN guard THEN TRUE ELSE hazard END
 * 3. SUBQUERY_GUARD: Comparing inline guarded subqueries vs flattened expressions.
 *
 * Catches critical optimizer omissions, incorrect predicate reordering, and executor SIGFPE crashes.
 * </p>
 */
public class GaussDBSCorOracle implements TestOracle<GaussDBGlobalState> {

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBSCorReproducer reproducer;

    public GaussDBSCorOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public enum SCorMode {
        AND_SHORT_CIRCUIT,
        OR_SHORT_CIRCUIT,
        SUBQUERY_GUARD
    }

    public static final class SCorBundle {

        private final SCorMode mode;
        private final String query1;
        private final String query2;

        public SCorBundle(SCorMode mode, String query1, String query2) {
            this.mode = mode;
            this.query1 = query1;
            this.query2 = query2;
        }

        public SCorMode getMode() {
            return mode;
        }

        public String getQuery1() {
            return query1;
        }

        public String getQuery2() {
            return query2;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());

        List<GaussDBColumn> numericCols = table.getColumns().stream()
                .filter(c -> c.getType() == GaussDBDataType.INT || c.getType() == GaussDBDataType.DECIMAL)
                .collect(Collectors.toList());

        if (numericCols.isEmpty()) {
            throw new IgnoreMeException();
        }

        GaussDBColumn targetCol = Randomly.fromList(numericCols);
        String c = targetCol.getName();
        String tableName = table.getName();

        String projCols = table.getColumns().stream().map(GaussDBColumn::getName).collect(Collectors.joining(", "));
        long threshold = Randomly.fromOptions(0, 1, 5, 10, 50);

        SCorMode mode = Randomly.fromOptions(SCorMode.values());
        String q1;
        String q2;

        switch (mode) {
        case AND_SHORT_CIRCUIT:
            // Safe guard: (c IS NOT NULL AND c <> 0)
            // Hazardous expression: (1000 / NULLIF(c, 0) > threshold)
            q1 = "SELECT " + projCols + " FROM " + tableName + " WHERE (" + c + " IS NOT NULL AND " + c + " <> 0) AND (1000 / NULLIF(" + c + ", 0) > " + threshold + ")";
            q2 = "SELECT " + projCols + " FROM " + tableName + " WHERE CASE WHEN (" + c + " IS NOT NULL AND " + c + " <> 0) THEN (1000 / NULLIF(" + c + ", 0) > " + threshold + ") ELSE FALSE END";
            break;
        case OR_SHORT_CIRCUIT:
            // Safe guard: (c IS NULL OR c = 0)
            // Hazardous expression: (1000 / NULLIF(c, 0) <= threshold)
            q1 = "SELECT " + projCols + " FROM " + tableName + " WHERE (" + c + " IS NULL OR " + c + " = 0) OR (1000 / NULLIF(" + c + ", 0) <= " + threshold + ")";
            q2 = "SELECT " + projCols + " FROM " + tableName + " WHERE CASE WHEN (" + c + " IS NULL OR " + c + " = 0) THEN TRUE ELSE (1000 / NULLIF(" + c + ", 0) <= " + threshold + ") END";
            break;
        case SUBQUERY_GUARD:
            // Guarded inline subquery vs outer filtered query
            q1 = "SELECT " + projCols + " FROM (SELECT " + projCols + " FROM " + tableName + " WHERE " + c + " IS NOT NULL AND " + c + " <> 0) s WHERE (1000 / NULLIF(s." + c + ", 0) > " + threshold + ")";
            q2 = "SELECT " + projCols + " FROM " + tableName + " WHERE (" + c + " IS NOT NULL AND " + c + " <> 0) AND (1000 / NULLIF(" + c + ", 0) > " + threshold + ")";
            break;
        default:
            throw new IgnoreMeException();
        }

        SCorBundle bundle = new SCorBundle(mode, q1, q2);
        SQLConnection connection = state.getConnection();

        try (Statement statement = connection.createStatement()) {
            List<String> r1 = executeAndGetRows(statement, q1);
            List<String> r2 = executeAndGetRows(statement, q2);

            try {
                verifySCorInvariants(bundle, r1, r2);
            } catch (AssertionError e) {
                // Retry once to eliminate non-deterministic state
                r1 = executeAndGetRows(statement, q1);
                r2 = executeAndGetRows(statement, q2);
                try {
                    verifySCorInvariants(bundle, r1, r2);
                } catch (AssertionError ex) {
                    reproducer = new GaussDBSCorReproducer(bundle);
                    throw ex;
                }
            }
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        }
    }

    public static void verifySCorInvariants(SCorBundle bundle, List<String> r1, List<String> r2) {
        Set<String> s1 = new HashSet<>(r1);
        Set<String> s2 = new HashSet<>(r2);
        if (!s1.equals(s2)) {
            throw new AssertionError(String.format(
                    "SCor Short-Circuit Invariance Violation (%s)!\nQuery1: %s\nRows: %s\nQuery2: %s\nRows: %s",
                    bundle.getMode(), bundle.getQuery1(), r1, bundle.getQuery2(), r2));
        }
    }

    private List<String> executeAndGetRows(Statement statement, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>(cols);
                for (int i = 1; i <= cols; i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

    @Override
    public Reproducer<GaussDBGlobalState> getLastReproducer() {
        return reproducer;
    }

}
