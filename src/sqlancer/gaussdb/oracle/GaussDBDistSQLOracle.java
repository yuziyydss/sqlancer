package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * DistSQL (SIGMOD '26, Tsinghua WingTecher) Centralized vs Distributed Dual-Mode Oracle for GaussDB.
 *
 * <p>
 * Evaluates semantic equivalence between Centralized Single-Node Execution and Distributed Stream Parallel Execution:
 * 1. DISTRIBUTED_AGGREGATION: Two-phase distributed aggregation (Partial Agg on DataNodes + Final Agg on Coordinator)
 *    versus Centralized Single-Node aggregation.
 * 2. DISTRIBUTED_JOIN_STREAMING: Cross-node Stream Redistribute / Broadcast Joins versus Centralized Local Joins.
 * 3. DISTRIBUTED_WINDOW_STREAMING: Distributed Window Function partition shuffling versus Serial Window Aggregation.
 * 4. SMP_STREAM_DIFFERENTIAL: Parallel SMP Stream Execution (query_dop &gt; 1, enable_stream_operator=on) versus
 *    Single-Thread Serial Execution (query_dop = 1, enable_stream_operator=off).
 *
 * Catches critical bugs in Distributed Stream Shuffling, socket buffer tuple drops, partial-to-final aggregation state loss,
 * and coordinator node collation divergences.
 * </p>
 */
public class GaussDBDistSQLOracle implements TestOracle<GaussDBGlobalState> {

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBDistSQLReproducer reproducer;

    public GaussDBDistSQLOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
        errors.add("Stream");
        errors.add("Stream thread");
        errors.add("communication error");
        errors.add("connection to datanode failed");
    }

    public enum DistSQLMode {
        DISTRIBUTED_AGGREGATION,
        DISTRIBUTED_JOIN_STREAMING,
        DISTRIBUTED_WINDOW_STREAMING,
        SMP_STREAM_DIFFERENTIAL
    }

    public static final class DistSQLBundle {

        private final DistSQLMode mode;
        private final String query;
        private final List<String> centralizedGucs;
        private final List<String> distributedGucs;

        public DistSQLBundle(DistSQLMode mode, String query, List<String> centralizedGucs, List<String> distributedGucs) {
            this.mode = mode;
            this.query = query;
            this.centralizedGucs = centralizedGucs;
            this.distributedGucs = distributedGucs;
        }

        public DistSQLMode getMode() {
            return mode;
        }

        public String getQuery() {
            return query;
        }

        public List<String> getCentralizedGucs() {
            return centralizedGucs;
        }

        public List<String> getDistributedGucs() {
            return distributedGucs;
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

        GaussDBTable tableA = Randomly.fromList(allTables);
        List<GaussDBColumn> colsA = tableA.getColumns();

        List<GaussDBColumn> numericCols = colsA.stream()
                .filter(c -> c.getType() == GaussDBDataType.INT || c.getType() == GaussDBDataType.DECIMAL)
                .collect(Collectors.toList());

        GaussDBColumn grpCol = colsA.get(0);
        GaussDBColumn numCol = numericCols.isEmpty() ? grpCol : Randomly.fromList(numericCols);

        DistSQLMode mode = Randomly.fromOptions(DistSQLMode.values());
        String query;

        switch (mode) {
        case DISTRIBUTED_AGGREGATION:
            // Stresses Partial Agg on DN -> Final Agg on CN vs Centralized Single-Node Agg
            query = "SELECT " + grpCol.getName() + ", COUNT(*), COALESCE(SUM(" + numCol.getName() + "), 0), "
                    + "MIN(" + numCol.getName() + "), MAX(" + numCol.getName() + ") FROM " + tableA.getName()
                    + " GROUP BY " + grpCol.getName();
            break;
        case DISTRIBUTED_JOIN_STREAMING: {
            // Stresses Stream Broadcast / Stream Redistribute Join vs Local Centralized Join
            GaussDBTable tableB = Randomly.fromList(allTables);
            GaussDBColumn colB = Randomly.fromList(tableB.getColumns());
            query = "SELECT a." + grpCol.getName() + ", b." + colB.getName() + " FROM " + tableA.getName() + " a "
                    + "JOIN " + tableB.getName() + " b ON a." + grpCol.getName() + " = b." + colB.getName();
            break;
        }
        case DISTRIBUTED_WINDOW_STREAMING:
            // Stresses Distributed Window Function frame partitioning across Stream workers
            query = "SELECT " + grpCol.getName() + ", " + numCol.getName() + ", "
                    + "DENSE_RANK() OVER (PARTITION BY " + grpCol.getName() + " ORDER BY " + numCol.getName() + " ASC NULLS LAST) "
                    + "FROM " + tableA.getName();
            break;
        case SMP_STREAM_DIFFERENTIAL: {
            GaussDBExpressionGenerator gen = new GaussDBExpressionGenerator(state).setColumns(colsA);
            GaussDBExpression whereExpr = gen.generatePredicate();
            String whereSql = GaussDBToStringVisitor.asString(whereExpr);
            String projCols = colsA.stream().map(GaussDBColumn::getName).collect(Collectors.joining(", "));
            query = "SELECT " + projCols + " FROM " + tableA.getName() + " WHERE (" + whereSql + ")";
            break;
        }
        default:
            throw new IgnoreMeException();
        }

        List<String> centralizedGucs = Arrays.asList(
                "SET query_dop = 1;",
                "SET enable_stream_operator = off;",
                "SET enable_fast_query_shipping = on;"
        );

        List<String> distributedGucs = Arrays.asList(
                "SET query_dop = 4;",
                "SET enable_stream_operator = on;",
                "SET enable_stream_replication = on;",
                "SET enable_fast_query_shipping = off;"
        );

        DistSQLBundle bundle = new DistSQLBundle(mode, query, centralizedGucs, distributedGucs);
        SQLConnection connection = state.getConnection();

        try (Statement statement = connection.createStatement()) {
            // 1. Run Centralized Mode
            statement.execute("RESET ALL;");
            for (String guc : centralizedGucs) {
                try {
                    statement.execute(guc);
                } catch (SQLException ignored) {
                }
            }
            List<String> centralizedRows = executeAndGetRows(statement, query);

            // 2. Run Distributed / SMP Stream Mode
            statement.execute("RESET ALL;");
            for (String guc : distributedGucs) {
                try {
                    statement.execute(guc);
                } catch (SQLException ignored) {
                }
            }
            List<String> distributedRows = executeAndGetRows(statement, query);

            // 3. Reset GUCs to prevent polluting subsequent tests
            statement.execute("RESET ALL;");

            try {
                verifyDistSQLInvariants(bundle, centralizedRows, distributedRows);
            } catch (AssertionError e) {
                // Retry once to eliminate transient timing
                statement.execute("RESET ALL;");
                for (String guc : centralizedGucs) {
                    try {
                        statement.execute(guc);
                    } catch (SQLException ignored) {
                    }
                }
                centralizedRows = executeAndGetRows(statement, query);

                statement.execute("RESET ALL;");
                for (String guc : distributedGucs) {
                    try {
                        statement.execute(guc);
                    } catch (SQLException ignored) {
                    }
                }
                distributedRows = executeAndGetRows(statement, query);
                statement.execute("RESET ALL;");

                try {
                    verifyDistSQLInvariants(bundle, centralizedRows, distributedRows);
                } catch (AssertionError ex) {
                    reproducer = new GaussDBDistSQLReproducer(bundle);
                    throw ex;
                }
            }
        } catch (SQLException e) {
            try (Statement resetStmt = connection.createStatement()) {
                resetStmt.execute("RESET ALL;");
            } catch (Exception ignored) {
            }
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        }
    }

    public static void verifyDistSQLInvariants(DistSQLBundle bundle, List<String> centralizedRows, List<String> distributedRows) {
        Map<String, Integer> mapCentral = toCountMap(centralizedRows);
        Map<String, Integer> mapDist = toCountMap(distributedRows);

        if (!mapCentral.equals(mapDist)) {
            throw new AssertionError(String.format(
                    "DistSQL Centralized vs Distributed Equivalence Violation (%s)!\n"
                            + "Query: %s\nCentralized Rows (%d): %s\nDistributed Rows (%d): %s",
                    bundle.getMode(), bundle.getQuery(),
                    centralizedRows.size(), centralizedRows,
                    distributedRows.size(), distributedRows));
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
