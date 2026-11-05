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
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.GaussDBToStringVisitor;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

/**
 * Pinolo (USENIX ATC '23) Subsumption-based Query Synthesis & Differential Oracle for GaussDB.
 *
 * <p>
 * Instead of relying strictly on full semantic equivalence (Q1 ≡ Q2), Pinolo constructs a guaranteed
 * subsumption order:
 * P_sub ≡ (P_base AND P_extra) &rArr; P_base &rArr; P_super ≡ (P_base OR P_extra).
 *
 * This implies mathematically in SQL 3-valued logic that:
 * R(Q_sub) &sube; R(Q_base) &sube; R(Q_super), and
 * COUNT(Q_sub) &le; COUNT(Q_base) &le; COUNT(Q_super).
 *
 * Catches optimizer bugs in predicate pushdown, subquery unnesting, bitmap scan merge, and index condition evaluations.
 * </p>
 */
public class GaussDBPinoloOracle implements TestOracle<GaussDBGlobalState> {

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBPinoloReproducer reproducer;

    public GaussDBPinoloOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public enum PinoloMode {
        SET_SUBSUMPTION,
        COUNT_BOUND
    }

    public static final class PinoloBundle {

        private final PinoloMode mode;
        private final String baseQuery;
        private final String subQuery;
        private final String superQuery;

        public PinoloBundle(PinoloMode mode, String baseQuery, String subQuery, String superQuery) {
            this.mode = mode;
            this.baseQuery = baseQuery;
            this.subQuery = subQuery;
            this.superQuery = superQuery;
        }

        public PinoloMode getMode() {
            return mode;
        }

        public String getBaseQuery() {
            return baseQuery;
        }

        public String getSubQuery() {
            return subQuery;
        }

        public String getSuperQuery() {
            return superQuery;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());

        List<GaussDBColumn> columns = table.getColumns();
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }

        GaussDBExpressionGenerator gen = new GaussDBExpressionGenerator(state).setColumns(columns);
        GaussDBExpression basePred = gen.generatePredicate();
        GaussDBExpression extraPred = gen.generatePredicate();

        if (basePred == null || extraPred == null) {
            throw new IgnoreMeException();
        }

        String basePredSql = GaussDBToStringVisitor.asString(basePred);
        String extraPredSql = GaussDBToStringVisitor.asString(extraPred);

        PinoloMode mode = Randomly.fromOptions(PinoloMode.values());
        String tableName = table.getName();

        String baseQuery;
        String subQuery;
        String superQuery;

        if (mode == PinoloMode.COUNT_BOUND) {
            baseQuery = "SELECT COUNT(*) FROM " + tableName + " WHERE (" + basePredSql + ")";
            subQuery = "SELECT COUNT(*) FROM " + tableName + " WHERE (" + basePredSql + ") AND (" + extraPredSql + ")";
            superQuery = "SELECT COUNT(*) FROM " + tableName + " WHERE (" + basePredSql + ") OR (" + extraPredSql + ")";
        } else {
            String projCols = columns.stream().map(GaussDBColumn::getName).collect(Collectors.joining(", "));
            baseQuery = "SELECT " + projCols + " FROM " + tableName + " WHERE (" + basePredSql + ")";
            subQuery = "SELECT " + projCols + " FROM " + tableName + " WHERE (" + basePredSql + ") AND (" + extraPredSql + ")";
            superQuery = "SELECT " + projCols + " FROM " + tableName + " WHERE (" + basePredSql + ") OR (" + extraPredSql + ")";
        }

        PinoloBundle bundle = new PinoloBundle(mode, baseQuery, subQuery, superQuery);
        SQLConnection connection = state.getConnection();

        try (Statement statement = connection.createStatement()) {
            List<String> subRows = executeAndGetRows(statement, subQuery);
            List<String> baseRows = executeAndGetRows(statement, baseQuery);
            List<String> superRows = executeAndGetRows(statement, superQuery);

            try {
                verifyPinoloInvariants(bundle, subRows, baseRows, superRows);
            } catch (AssertionError e) {
                // Retry once to eliminate non-deterministic expressions
                subRows = executeAndGetRows(statement, subQuery);
                baseRows = executeAndGetRows(statement, baseQuery);
                superRows = executeAndGetRows(statement, superQuery);
                try {
                    verifyPinoloInvariants(bundle, subRows, baseRows, superRows);
                } catch (AssertionError ex) {
                    reproducer = new GaussDBPinoloReproducer(bundle);
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

    public static void verifyPinoloInvariants(PinoloBundle bundle, List<String> subRows, List<String> baseRows,
            List<String> superRows) {
        if (bundle.getMode() == PinoloMode.SET_SUBSUMPTION) {
            Set<String> subSet = new HashSet<>(subRows);
            Set<String> baseSet = new HashSet<>(baseRows);
            Set<String> superSet = new HashSet<>(superRows);

            if (!baseSet.containsAll(subSet)) {
                Set<String> missing = new HashSet<>(subSet);
                missing.removeAll(baseSet);
                throw new AssertionError("Pinolo Subsumption Violation: Subset query contains rows not in Base query!\n"
                        + "Missing in base: " + missing + "\nSubQuery: " + bundle.getSubQuery() + "\nBaseQuery: "
                        + bundle.getBaseQuery());
            }

            if (!superSet.containsAll(baseSet)) {
                Set<String> missing = new HashSet<>(baseSet);
                missing.removeAll(superSet);
                throw new AssertionError("Pinolo Subsumption Violation: Base query contains rows not in Super query!\n"
                        + "Missing in super: " + missing + "\nBaseQuery: " + bundle.getBaseQuery() + "\nSuperQuery: "
                        + bundle.getSuperQuery());
            }
        } else if (bundle.getMode() == PinoloMode.COUNT_BOUND) {
            if (subRows.isEmpty() || baseRows.isEmpty() || superRows.isEmpty()) {
                throw new AssertionError("Pinolo Count Violation: Empty result from COUNT(*)");
            }
            long subCount = Long.parseLong(subRows.get(0).replaceAll("[\\[\\] ]", ""));
            long baseCount = Long.parseLong(baseRows.get(0).replaceAll("[\\[\\] ]", ""));
            long superCount = Long.parseLong(superRows.get(0).replaceAll("[\\[\\] ]", ""));

            if (subCount > baseCount || baseCount > superCount) {
                throw new AssertionError(String.format(
                        "Pinolo Cardinality Bound Violation: Expected subCount (%d) <= baseCount (%d) <= superCount (%d)!\n"
                                + "SubQuery: %s\nBaseQuery: %s\nSuperQuery: %s",
                        subCount, baseCount, superCount, bundle.getSubQuery(), bundle.getBaseQuery(),
                        bundle.getSuperQuery()));
            }
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
