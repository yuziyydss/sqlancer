package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * BuzzHouse / IVM Testing (VLDB/SIGMOD '25) Materialized View & Incremental Refresh Oracle for GaussDB.
 *
 * <p>
 * Tests Materialized View consistency and refresh invariants under mixed DML mutations:
 * 1. Materialized View Definition Equivalence: After REFRESH MATERIALIZED VIEW, querying the physical
 *    materialized view MUST produce the exact same tuple multiset as evaluating the underlying view
 *    query definition directly against the modified base tables.
 * 2. Filtered View Pushdown Invariance: Applying outer WHERE predicates to the materialized view must produce
 *    results equivalent to wrapping the view definition inside an inline subquery with the same filter.
 *
 * Exposes critical defects in GaussDB's incremental refresh delta log tracking, view inlining, and MV state synchronization.
 * </p>
 */
public class GaussDBIVMOracle implements TestOracle<GaussDBGlobalState> {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBIVMReproducer reproducer;

    public GaussDBIVMOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
        errors.add("materialized view");
        errors.add("already exists");
        errors.add("cannot refresh");
    }

    public enum IVMMode {
        FULL_REFRESH_CONSISTENCY,
        FILTERED_VIEW_PUSHDOWN
    }

    public static final class IVMBundle {

        private final IVMMode mode;
        private final String mvName;
        private final String createMvSql;
        private final String queryDef;
        private final List<String> dmlMutations;
        private final String refreshSql;
        private final String mvQuery;
        private final String baseQuery;
        private final String cleanupSql;

        public IVMBundle(IVMMode mode, String mvName, String createMvSql, String queryDef,
                List<String> dmlMutations, String refreshSql, String mvQuery, String baseQuery, String cleanupSql) {
            this.mode = mode;
            this.mvName = mvName;
            this.createMvSql = createMvSql;
            this.queryDef = queryDef;
            this.dmlMutations = dmlMutations;
            this.refreshSql = refreshSql;
            this.mvQuery = mvQuery;
            this.baseQuery = baseQuery;
            this.cleanupSql = cleanupSql;
        }

        public IVMMode getMode() {
            return mode;
        }

        public String getMvName() {
            return mvName;
        }

        public String getCreateMvSql() {
            return createMvSql;
        }

        public String getQueryDef() {
            return queryDef;
        }

        public List<String> getDmlMutations() {
            return dmlMutations;
        }

        public String getRefreshSql() {
            return refreshSql;
        }

        public String getMvQuery() {
            return mvQuery;
        }

        public String getBaseQuery() {
            return baseQuery;
        }

        public String getCleanupSql() {
            return cleanupSql;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        List<GaussDBTable> allTables = state.getSchema().getDatabaseTables().stream()
                .filter(t -> !t.isView() && !t.getColumns().isEmpty())
                .collect(Collectors.toList());

        if (allTables.isEmpty()) {
            throw new IgnoreMeException();
        }

        GaussDBTable table = Randomly.fromList(allTables);
        List<GaussDBColumn> cols = table.getColumns();

        List<GaussDBColumn> numericCols = cols.stream()
                .filter(c -> c.getType() == GaussDBDataType.INT || c.getType() == GaussDBDataType.DECIMAL)
                .collect(Collectors.toList());

        long id = COUNTER.incrementAndGet();
        String mvName = "mv_ivm_" + id;
        String t = table.getName();

        String queryDef;
        if (!numericCols.isEmpty() && cols.size() >= 2) {
            GaussDBColumn grpCol = cols.get(0);
            GaussDBColumn numCol = numericCols.get(0);
            queryDef = "SELECT " + grpCol.getName() + ", COUNT(*) AS cnt, COALESCE(SUM(" + numCol.getName()
                    + "), 0) AS total FROM " + t + " GROUP BY " + grpCol.getName();
        } else {
            String projCols = cols.stream().map(GaussDBColumn::getName).collect(Collectors.joining(", "));
            queryDef = "SELECT " + projCols + " FROM " + t;
        }

        String createMvSql = "CREATE MATERIALIZED VIEW " + mvName + " AS " + queryDef + ";";
        String refreshSql = "REFRESH MATERIALIZED VIEW " + mvName + ";";
        String cleanupSql = "DROP MATERIALIZED VIEW IF EXISTS " + mvName + " CASCADE;";

        List<String> dmlMutations = new ArrayList<>();
        // Inject DML mutation statements on the base table
        GaussDBColumn firstCol = cols.get(0);
        dmlMutations.add("UPDATE " + t + " SET " + firstCol.getName() + " = " + firstCol.getName() + ";");
        if (Randomly.getBoolean()) {
            dmlMutations.add("DELETE FROM " + t + " WHERE " + firstCol.getName() + " IS NULL;");
        }

        IVMMode mode = Randomly.fromOptions(IVMMode.values());
        String mvQuery;
        String baseQuery;

        if (mode == IVMMode.FILTERED_VIEW_PUSHDOWN && !numericCols.isEmpty() && cols.size() >= 2) {
            mvQuery = "SELECT * FROM " + mvName + " WHERE cnt > 0;";
            baseQuery = "SELECT * FROM (" + queryDef + ") AS v WHERE cnt > 0;";
        } else {
            mvQuery = "SELECT * FROM " + mvName + ";";
            baseQuery = "SELECT * FROM (" + queryDef + ") AS v;";
        }

        IVMBundle bundle = new IVMBundle(mode, mvName, createMvSql, queryDef, dmlMutations, refreshSql, mvQuery,
                baseQuery, cleanupSql);
        SQLConnection connection = state.getConnection();

        try (Statement statement = connection.createStatement()) {
            // 1. Create Materialized View
            statement.execute(createMvSql);

            // 2. Perform DML mutations on base tables
            for (String dml : dmlMutations) {
                try {
                    statement.execute(dml);
                } catch (SQLException ignored) {
                }
            }

            // 3. Refresh Materialized View
            statement.execute(refreshSql);

            // 4. Differential Evaluation: Querying MV vs Querying base query directly
            List<String> mvRows = executeAndGetRows(statement, mvQuery);
            List<String> baseRows = executeAndGetRows(statement, baseQuery);

            try {
                verifyIVMInvariants(bundle, mvRows, baseRows);
            } catch (AssertionError e) {
                reproducer = new GaussDBIVMReproducer(bundle);
                throw e;
            } finally {
                try (Statement cleanStmt = connection.createStatement()) {
                    cleanStmt.execute(cleanupSql);
                } catch (Exception ignored) {
                }
            }
        } catch (SQLException e) {
            try (Statement cleanStmt = connection.createStatement()) {
                cleanStmt.execute(cleanupSql);
            } catch (Exception ignored) {
            }
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        }
    }

    public static void verifyIVMInvariants(IVMBundle bundle, List<String> mvRows, List<String> baseRows) {
        Map<String, Integer> m1 = toCountMap(mvRows);
        Map<String, Integer> m2 = toCountMap(baseRows);

        if (!m1.equals(m2)) {
            throw new AssertionError(String.format(
                    "BuzzHouse / IVM Materialized View Invariant Violation (%s)!\n"
                            + "MV Query: %s\nMV Rows (%d): %s\nBase Query: %s\nBase Rows (%d): %s\nDiff detected after REFRESH!",
                    bundle.getMode(), bundle.getMvQuery(), mvRows.size(), mvRows,
                    bundle.getBaseQuery(), baseRows.size(), baseRows));
        }
    }

    private static Map<String, Integer> toCountMap(List<String> list) {
        Map<String, Integer> map = new HashMap<>();
        for (String item : list) {
            map.put(item, map.getOrDefault(item, 0) + 1);
        }
        return map;
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
