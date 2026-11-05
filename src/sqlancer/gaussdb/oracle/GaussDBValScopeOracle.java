package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
import sqlancer.gaussdb.GaussDBToStringVisitor;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

/**
 * ValScope (OSDI '26 Best Paper Award) Value-Semantic Logic Bug Oracle for GaussDB.
 *
 * <p>
 * Implements the Unified SQL Query Approximation and Value Perturbation Model: applies controlled scalar perturbations
 * (&delta;) to expressions and verifies value-semantic homomorphic relations (aggregate sum/avg/min/max shifting,
 * window rank monotonicity invariance, and scalar algebraic round-trips) under complex CBO optimizations.
 * </p>
 */
public class GaussDBValScopeOracle implements TestOracle<GaussDBGlobalState> {

    private static final String TABLE_ALIAS = "t";
    private static final double EPSILON = 1e-3;

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBValScopeReproducer reproducer;
    private String lastQueryString;

    public GaussDBValScopeOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public enum ValScopeMode {
        AGGREGATE_PERTURBATION,
        WINDOW_MONOTONICITY,
        SCALAR_ROUNDTRIP
    }

    public static final class ValScopeBundle {

        private final ValScopeMode mode;
        private final String baseQuery;
        private final String perturbedQuery;
        private final long shiftK;

        public ValScopeBundle(ValScopeMode mode, String baseQuery, String perturbedQuery, long shiftK) {
            this.mode = mode;
            this.baseQuery = baseQuery;
            this.perturbedQuery = perturbedQuery;
            this.shiftK = shiftK;
        }

        public ValScopeMode getMode() {
            return mode;
        }

        public String getBaseQuery() {
            return baseQuery;
        }

        public String getPerturbedQuery() {
            return perturbedQuery;
        }

        public long getShiftK() {
            return shiftK;
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

        GaussDBColumn col = Randomly.fromList(numericCols);
        long shiftK = state.getRandomly().getInteger(1, 50);
        if (Randomly.getBoolean()) {
            shiftK = -shiftK;
        }

        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(table.getColumns());
        generator.setTableAlias(TABLE_ALIAS);
        GaussDBExpression wherePredicate = generator.generateExpression(GaussDBDataType.BOOLEAN);

        ValScopeBundle bundle;
        switch (Randomly.fromOptions(0, 1, 2)) {
        case 0:
            bundle = buildAggregatePerturbationBundle(table, col, wherePredicate, shiftK);
            break;
        case 1:
            bundle = buildWindowMonotonicityBundle(table, col, wherePredicate, shiftK);
            break;
        case 2:
            bundle = buildScalarRoundtripBundle(table, col, wherePredicate, shiftK);
            break;
        default:
            throw new AssertionError();
        }

        verifyBundle(bundle);
    }

    public static ValScopeBundle buildAggregatePerturbationBundle(GaussDBTable table, GaussDBColumn col,
            GaussDBExpression wherePredicate, long shiftK) {
        String predStr = GaussDBToStringVisitor.asString(wherePredicate);
        String colName = col.getName();
        String tbl = table.getName();

        String baseQuery = String.format(
                "SELECT COUNT(*), COALESCE(SUM(%s), 0), COALESCE(AVG(%s), 0), COALESCE(MIN(%s), 0), COALESCE(MAX(%s), 0) FROM %s %s WHERE %s",
                colName, colName, colName, colName, tbl, TABLE_ALIAS, predStr);

        String perturbedQuery = String.format(
                "SELECT COUNT(*), COALESCE(SUM(%s + (%d)), 0), COALESCE(AVG(%s + (%d)), 0), COALESCE(MIN(%s + (%d)), 0), COALESCE(MAX(%s + (%d)), 0) FROM %s %s WHERE %s",
                colName, shiftK, colName, shiftK, colName, shiftK, colName, shiftK, tbl, TABLE_ALIAS, predStr);

        return new ValScopeBundle(ValScopeMode.AGGREGATE_PERTURBATION, baseQuery, perturbedQuery, shiftK);
    }

    public static ValScopeBundle buildWindowMonotonicityBundle(GaussDBTable table, GaussDBColumn col,
            GaussDBExpression wherePredicate, long shiftK) {
        String predStr = GaussDBToStringVisitor.asString(wherePredicate);
        String colName = col.getName();
        String tbl = table.getName();

        String baseQuery = String.format(
                "SELECT DENSE_RANK() OVER (ORDER BY %s ASC NULLS LAST) FROM %s %s WHERE %s ORDER BY %s ASC NULLS LAST",
                colName, tbl, TABLE_ALIAS, predStr, colName);

        String perturbedQuery = String.format(
                "SELECT DENSE_RANK() OVER (ORDER BY (%s + (%d)) ASC NULLS LAST) FROM %s %s WHERE %s ORDER BY (%s + (%d)) ASC NULLS LAST",
                colName, shiftK, tbl, TABLE_ALIAS, predStr, colName, shiftK);

        return new ValScopeBundle(ValScopeMode.WINDOW_MONOTONICITY, baseQuery, perturbedQuery, shiftK);
    }

    public static ValScopeBundle buildScalarRoundtripBundle(GaussDBTable table, GaussDBColumn col,
            GaussDBExpression wherePredicate, long shiftK) {
        String predStr = GaussDBToStringVisitor.asString(wherePredicate);
        String colName = col.getName();
        String tbl = table.getName();

        String baseQuery = String.format("SELECT %s FROM %s %s WHERE %s",
                colName, tbl, TABLE_ALIAS, predStr);

        String perturbedQuery = String.format("SELECT ((%s + (%d)) - (%d)) FROM %s %s WHERE %s",
                colName, shiftK, shiftK, tbl, TABLE_ALIAS, predStr);

        return new ValScopeBundle(ValScopeMode.SCALAR_ROUNDTRIP, baseQuery, perturbedQuery, shiftK);
    }

    private void verifyBundle(ValScopeBundle bundle) throws SQLException {
        this.lastQueryString = bundle.getBaseQuery();
        SQLConnection con = state.getConnection();

        try (Statement statement = con.createStatement()) {
            List<String> baseRows;
            try {
                baseRows = executeAndGetRows(statement, bundle.getBaseQuery());
            } catch (SQLException e) {
                if (errors.errorIsExpected(e.getMessage())) {
                    return;
                }
                throw e;
            }

            List<String> pertRows;
            try {
                pertRows = executeAndGetRows(statement, bundle.getPerturbedQuery());
            } catch (SQLException e) {
                if (errors.errorIsExpected(e.getMessage())) {
                    return;
                }
                this.reproducer = new GaussDBValScopeReproducer(bundle);
                throw new AssertionError(String.format(
                        "ValScope Perturbed Query failed while Base Query succeeded!%nBase: %s%nPerturbed: %s%nError: %s",
                        bundle.getBaseQuery(), bundle.getPerturbedQuery(), e.getMessage()), e);
            }

            switch (bundle.getMode()) {
            case AGGREGATE_PERTURBATION:
                verifyAggregatePerturbation(bundle, baseRows, pertRows);
                break;
            case WINDOW_MONOTONICITY:
            case SCALAR_ROUNDTRIP:
                if (!areEquivalent(baseRows, pertRows)) {
                    this.reproducer = new GaussDBValScopeReproducer(bundle);
                    throw new AssertionError(String.format(
                            "ValScope Value-Semantic Invariance Violation (%s)!%nBase Rows (%d): %s%nPerturbed Rows (%d): %s%nBase Query: %s%nPerturbed Query: %s",
                            bundle.getMode(), baseRows.size(), baseRows, pertRows.size(), pertRows,
                            bundle.getBaseQuery(), bundle.getPerturbedQuery()));
                }
                break;
            default:
                throw new AssertionError();
            }
        }
    }

    public static void verifyAggregatePerturbation(ValScopeBundle bundle, List<String> baseRows, List<String> pertRows) {
        if (baseRows.isEmpty() || pertRows.isEmpty()) {
            throw new AssertionError("Empty result set from aggregate query!");
        }

        String[] baseVals = parseRow(baseRows.get(0));
        String[] pertVals = parseRow(pertRows.get(0));

        if (baseVals.length != 5 || pertVals.length != 5) {
            throw new AssertionError("Invalid row format from aggregate query!");
        }

        long count1 = (long) Double.parseDouble(baseVals[0]);
        long count2 = (long) Double.parseDouble(pertVals[0]);

        if (count1 != count2) {
            throw new AssertionError(String.format(
                    "ValScope Row Count Mismatch! Base: %d, Perturbed: %d", count1, count2));
        }

        if (count1 == 0) {
            return;
        }

        double sum1 = Double.parseDouble(baseVals[1]);
        double sum2 = Double.parseDouble(pertVals[1]);
        double expectedSum2 = sum1 + count1 * bundle.getShiftK();
        assertValueClose("SUM", expectedSum2, sum2, bundle);

        double avg1 = Double.parseDouble(baseVals[2]);
        double avg2 = Double.parseDouble(pertVals[2]);
        double expectedAvg2 = avg1 + bundle.getShiftK();
        assertValueClose("AVG", expectedAvg2, avg2, bundle);

        double min1 = Double.parseDouble(baseVals[3]);
        double min2 = Double.parseDouble(pertVals[3]);
        double expectedMin2 = min1 + bundle.getShiftK();
        assertValueClose("MIN", expectedMin2, min2, bundle);

        double max1 = Double.parseDouble(baseVals[4]);
        double max2 = Double.parseDouble(pertVals[4]);
        double expectedMax2 = max1 + bundle.getShiftK();
        assertValueClose("MAX", expectedMax2, max2, bundle);
    }

    private static void assertValueClose(String metric, double expected, double actual, ValScopeBundle bundle) {
        double diff = Math.abs(expected - actual);
        double maxTol = Math.max(1.0, Math.abs(expected)) * EPSILON;
        if (diff > maxTol) {
            throw new AssertionError(String.format(
                    "ValScope Value-Semantic Aggregate Violation on %s!%nExpected: %f, Actual: %f (diff: %f, tol: %f)%nShift K: %d%nBase: %s%nPerturbed: %s",
                    metric, expected, actual, diff, maxTol, bundle.getShiftK(), bundle.getBaseQuery(), bundle.getPerturbedQuery()));
        }
    }

    private static String[] parseRow(String row) {
        String clean = row.trim();
        if (clean.startsWith("[") && clean.endsWith("]")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        return clean.split(",\\s*");
    }

    public static boolean areEquivalent(List<String> list1, List<String> list2) {
        if (list1.size() != list2.size()) {
            return false;
        }
        List<String> sorted1 = new ArrayList<>(list1);
        List<String> sorted2 = new ArrayList<>(list2);
        sorted1.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
        sorted2.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
        return sorted1.equals(sorted2);
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

    @Override
    public String getLastQueryString() {
        return lastQueryString;
    }
}
