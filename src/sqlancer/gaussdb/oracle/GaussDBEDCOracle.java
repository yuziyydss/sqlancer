package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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
 * Equivalent Data Construction (EDC, SIGMOD'26) oracle for GaussDB.
 *
 * <p>
 * Evaluates domain homomorphism and affine data shifting invariants:
 * For relation T with numeric attribute x, an affine shift x' = x + K preserves
 * predicate selectivity isomorphism: σ_{x > C}(T) ≡ σ_{x' > (C + K)}(T_shifted),
 * and aggregate homomorphism: SUM(x') = SUM(x) + K * COUNT(x).
 *
 * Any divergence detects arithmetic overflow errors, index boundary off-by-one scans,
 * or constant folding anomalies in GaussDB.
 * </p>
 */
public class GaussDBEDCOracle implements TestOracle<GaussDBGlobalState> {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBEDCReproducer reproducer;

    public GaussDBEDCOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public static final class EDCBundle {
        private final List<String> setupSql;
        private final String baseQuery;
        private final String shiftedQuery;
        private final List<String> cleanupSql;

        public EDCBundle(List<String> setupSql, String baseQuery, String shiftedQuery, List<String> cleanupSql) {
            this.setupSql = Collections.unmodifiableList(new ArrayList<>(setupSql));
            this.baseQuery = baseQuery;
            this.shiftedQuery = shiftedQuery;
            this.cleanupSql = Collections.unmodifiableList(new ArrayList<>(cleanupSql));
        }

        public List<String> getSetupSql() {
            return setupSql;
        }

        public String getBaseQuery() {
            return baseQuery;
        }

        public String getShiftedQuery() {
            return shiftedQuery;
        }

        public List<String> getCleanupSql() {
            return cleanupSql;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());

        EDCBundle bundle = generateBundle(table);
        if (bundle == null) {
            return;
        }
        verifyBundle(bundle);
    }

    public static EDCBundle generateBundle(GaussDBTable baseTable) {
        List<GaussDBColumn> numericCols = baseTable.getColumns().stream()
                .filter(c -> isNumeric(c.getType()))
                .collect(Collectors.toList());

        if (numericCols.isEmpty()) {
            return null;
        }

        GaussDBColumn shiftCol = Randomly.fromList(numericCols);
        long k = Randomly.getNotCachedInteger(10, 500);
        long c = Randomly.getNotCachedInteger(0, 300);
        long cShift = c + k;

        long id = COUNTER.incrementAndGet();
        String shiftTable = "t_edc_" + id;
        String shiftColName = shiftCol.getName() + "_shift";

        List<String> selectParts = new ArrayList<>();
        List<String> nonShiftedCols = new ArrayList<>();
        for (GaussDBColumn col : baseTable.getColumns()) {
            if (col.getName().equals(shiftCol.getName())) {
                selectParts.add("(" + col.getName() + " + " + k + ") AS " + shiftColName);
            } else {
                selectParts.add(col.getName());
                nonShiftedCols.add(col.getName());
            }
        }

        List<String> setupSql = new ArrayList<>();
        List<String> cleanupSql = new ArrayList<>();

        cleanupSql.add("DROP TABLE IF EXISTS " + shiftTable + " CASCADE");
        setupSql.add("DROP TABLE IF EXISTS " + shiftTable + " CASCADE");
        setupSql.add("CREATE TEMPORARY TABLE " + shiftTable + " AS SELECT " + String.join(", ", selectParts)
                + " FROM " + baseTable.getName());

        String baseQuery;
        String shiftedQuery;

        if (Randomly.getBoolean() || nonShiftedCols.isEmpty()) {
            // Aggregate Homomorphism test: COUNT, SUM, MIN
            baseQuery = String.format(
                    "SELECT COUNT(*), (COALESCE(SUM(%s), 0) + (%d * COUNT(%s))), (COALESCE(MIN(%s), 0) + %d) FROM %s WHERE %s > %d",
                    shiftCol.getName(), k, shiftCol.getName(), shiftCol.getName(), k, baseTable.getName(), shiftCol.getName(), c);
            shiftedQuery = String.format(
                    "SELECT COUNT(*), COALESCE(SUM(%s), 0), COALESCE(MIN(%s), 0) FROM %s WHERE %s > %d",
                    shiftColName, shiftColName, shiftTable, shiftColName, cShift);
        } else {
            // Row-level projection isomorphism test
            String projCols = String.join(", ", nonShiftedCols);
            baseQuery = String.format("SELECT %s FROM %s WHERE %s > %d ORDER BY %s",
                    projCols, baseTable.getName(), shiftCol.getName(), c, projCols);
            shiftedQuery = String.format("SELECT %s FROM %s WHERE %s > %d ORDER BY %s",
                    projCols, shiftTable, shiftColName, cShift, projCols);
        }

        return new EDCBundle(setupSql, baseQuery, shiftedQuery, cleanupSql);
    }

    private static boolean isNumeric(GaussDBDataType type) {
        return type == GaussDBDataType.INT || type == GaussDBDataType.DECIMAL
                || type == GaussDBDataType.FLOAT || type == GaussDBDataType.REAL;
    }

    private void verifyBundle(EDCBundle bundle) throws SQLException {
        SQLConnection connection = state.getConnection();
        try (Statement statement = connection.createStatement()) {
            try {
                for (String sql : bundle.getSetupSql()) {
                    executeSetup(statement, sql);
                }

                List<String> baseRows = executeAndFetch(statement, bundle.getBaseQuery());
                List<String> shiftedRows = executeAndFetch(statement, bundle.getShiftedQuery());

                if (!baseRows.equals(shiftedRows)) {
                    reproducer = new GaussDBEDCReproducer(bundle.getSetupSql(), bundle.getBaseQuery(),
                            bundle.getShiftedQuery(), bundle.getCleanupSql());
                    state.getState().getLocalState().log(String.format(
                            "EDC Homomorphism Violation detected!%nBase Rows (%d): %s%nShifted Rows (%d): %s%nBase Q: %s%nShifted Q: %s",
                            baseRows.size(), baseRows, shiftedRows.size(), shiftedRows,
                            bundle.getBaseQuery(), bundle.getShiftedQuery()));
                    throw new AssertionError(String.format(
                            "EDC Homomorphism Violation: Base query returned %d rows, Shifted query returned %d rows!%nBase: %s%nShifted: %s",
                            baseRows.size(), shiftedRows.size(), baseRows, shiftedRows));
                }
            } catch (SQLException e) {
                if (errors.errorIsExpected(e.getMessage())) {
                    throw new IgnoreMeException();
                }
                throw e;
            } finally {
                for (String sql : bundle.getCleanupSql()) {
                    try {
                        statement.execute(sql);
                    } catch (SQLException ignored) {
                    }
                }
            }
        }
    }

    private void executeSetup(Statement statement, String sql) throws SQLException {
        try {
            statement.execute(sql);
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        }
    }

    private static List<String> executeAndFetch(Statement statement, String query) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(query)) {
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        row.append(", ");
                    }
                    row.append(String.valueOf(rs.getObject(i)));
                }
                rows.add(row.toString());
            }
        }
        rows.sort(Comparator.naturalOrder());
        return rows;
    }

    @Override
    public Reproducer<GaussDBGlobalState> getLastReproducer() {
        return reproducer;
    }
}
