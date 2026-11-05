package sqlancer.gaussdb.oracle;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.SQLConnection;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.gaussdb.GaussDBErrors;
import sqlancer.gaussdb.GaussDBGlobalState;

/**
 * TxCheck (OSDI '23, ETH Zurich) Direct Dependency Graph (DDG) Oracle for GaussDB.
 *
 * <p>
 * Evaluates transactional serializability guarantees via graph-based dependency analysis:
 * 1. ANTI_DEPENDENCY_CYCLE (rw-rw cycle): T1 reads x, writes y; T2 reads y, writes x.
 *    Under SERIALIZABLE, the conflict graph contains a cycle (T1 &rarr;rw T2 &rarr;rw T1).
 *    The database must detect this serialization anomaly and abort at least one transaction via SQLSTATE 40001.
 * 2. CAUSAL_READ_SKEW (ww-wr consistency): T1 writes x=1 then y=1. T2 reads y=1.
 *    T2 must never subsequently read the stale value x=0 under Repeatable Read / Serializable.
 *
 * Exposes bugs in GaussDB's SSI (Serializable Snapshot Isolation) SIREAD lock manager and SSI predicate locks.
 * </p>
 */
public class GaussDBTxCheckOracle implements TestOracle<GaussDBGlobalState> {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBTxCheckReproducer reproducer;

    public GaussDBTxCheckOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
        errors.add("could not serialize access");
        errors.add("deadlock detected");
        errors.add("lock wait timeout");
        errors.add("concurrent update");
    }

    public enum TxCheckMode {
        ANTI_DEPENDENCY_CYCLE,
        CAUSAL_READ_SKEW
    }

    public static final class TxCheckBundle {

        private final TxCheckMode mode;
        private final String tableName;
        private final String setupSql;
        private final String cleanupSql;

        public TxCheckBundle(TxCheckMode mode, String tableName, String setupSql, String cleanupSql) {
            this.mode = mode;
            this.tableName = tableName;
            this.setupSql = setupSql;
            this.cleanupSql = cleanupSql;
        }

        public TxCheckMode getMode() {
            return mode;
        }

        public String getTableName() {
            return tableName;
        }

        public String getSetupSql() {
            return setupSql;
        }

        public String getCleanupSql() {
            return cleanupSql;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        TxCheckMode mode = Randomly.fromOptions(TxCheckMode.values());
        long id = COUNTER.incrementAndGet();
        String tableName = "t_txcheck_" + id;

        String setupSql = String.format(
                "DROP TABLE IF EXISTS %s CASCADE; CREATE TABLE %s (id INT PRIMARY KEY, val INT); "
                        + "INSERT INTO %s VALUES (1, 0), (2, 0);",
                tableName, tableName, tableName);
        String cleanupSql = "DROP TABLE IF EXISTS " + tableName + " CASCADE;";

        TxCheckBundle bundle = new TxCheckBundle(mode, tableName, setupSql, cleanupSql);
        SQLConnection sqlConnection = state.getConnection();
        Connection con1 = sqlConnection.getRawConnection();
        if (con1 == null) {
            return;
        }

        Connection con2 = null;
        try (Statement initStatement = con1.createStatement()) {
            initStatement.execute(bundle.getSetupSql());

            String url = con1.getMetaData().getURL();
            String user = state.getOptions() != null ? state.getOptions().getUserName() : null;
            String pass = state.getOptions() != null ? state.getOptions().getPassword() : null;
            con2 = (user != null && pass != null) ? DriverManager.getConnection(url, user, pass)
                    : DriverManager.getConnection(url);
            copyCurrentSchema(con1, con2);

            try {
                runTxCheckSchedule(bundle, con1, con2);
            } catch (AssertionError e) {
                reproducer = new GaussDBTxCheckReproducer(bundle);
                throw e;
            } finally {
                try (Statement cleanStmt = con1.createStatement()) {
                    cleanStmt.execute(bundle.getCleanupSql());
                } catch (Exception ignored) {
                }
            }
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        } finally {
            if (con2 != null && !con2.isClosed()) {
                con2.close();
            }
        }
    }

    public static void runTxCheckSchedule(TxCheckBundle bundle, Connection con1, Connection con2) throws SQLException {
        String t = bundle.getTableName();
        switch (bundle.getMode()) {
        case ANTI_DEPENDENCY_CYCLE: {
            // T1 reads x (id=1), writes y (id=2).
            // T2 reads y (id=2), writes x (id=1).
            // Under SERIALIZABLE, conflict graph T1 ->rw T2 ->rw T1 forms a cycle.
            // Both committing to produce x=1 and y=1 violates serializability!
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch step1Latch = new CountDownLatch(2);
            CountDownLatch step2Latch = new CountDownLatch(1);
            AtomicBoolean tx1Committed = new AtomicBoolean(false);
            AtomicBoolean tx2Committed = new AtomicBoolean(false);
            AtomicReference<String> finalValues = new AtomicReference<>();

            executor.submit(() -> {
                try {
                    con1.setAutoCommit(false);
                    try (Statement s1 = con1.createStatement()) {
                        s1.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;");
                        executeAndGetRows(s1, "SELECT val FROM " + t + " WHERE id = 1");
                        step1Latch.countDown();
                        step2Latch.await();
                        s1.execute("UPDATE " + t + " SET val = 1 WHERE id = 2;");
                    }
                    con1.commit();
                    tx1Committed.set(true);
                } catch (Exception ignored) {
                    try {
                        con1.rollback();
                    } catch (SQLException ignoredRollback) {
                    }
                }
            });

            executor.submit(() -> {
                try {
                    con2.setAutoCommit(false);
                    try (Statement s2 = con2.createStatement()) {
                        s2.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;");
                        executeAndGetRows(s2, "SELECT val FROM " + t + " WHERE id = 2");
                        step1Latch.countDown();
                        step2Latch.await();
                        s2.execute("UPDATE " + t + " SET val = 1 WHERE id = 1;");
                    }
                    con2.commit();
                    tx2Committed.set(true);
                } catch (Exception ignored) {
                    try {
                        con2.rollback();
                    } catch (SQLException ignoredRollback) {
                    }
                }
            });

            try {
                step1Latch.await(5, TimeUnit.SECONDS);
                step2Latch.countDown();
                executor.shutdown();
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IgnoreMeException();
            } finally {
                con1.setAutoCommit(true);
                con2.setAutoCommit(true);
            }

            try (Statement checkStmt = con1.createStatement()) {
                List<String> rows = executeAndGetRows(checkStmt, "SELECT val FROM " + t + " ORDER BY id");
                finalValues.set(rows.toString());
            }

            verifyAntiDependencyCycle(tx1Committed.get(), tx2Committed.get(), finalValues.get(), t);
            break;
        }
        case CAUSAL_READ_SKEW: {
            // T1 updates x=1 then y=1 and commits.
            // T2 concurrently reads under REPEATABLE READ. If T2 reads y=1, it must never see x=0.
            con1.setAutoCommit(false);
            con2.setAutoCommit(false);
            try (Statement s1 = con1.createStatement(); Statement s2 = con2.createStatement()) {
                s1.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED;");
                s2.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;");

                s1.execute("UPDATE " + t + " SET val = 1 WHERE id = 1;");
                s1.execute("UPDATE " + t + " SET val = 1 WHERE id = 2;");
                con1.commit();

                List<String> rY = executeAndGetRows(s2, "SELECT val FROM " + t + " WHERE id = 2");
                List<String> rX = executeAndGetRows(s2, "SELECT val FROM " + t + " WHERE id = 1");
                con2.commit();

                verifyCausalReadSkew(rY, rX, t);
            } finally {
                con1.setAutoCommit(true);
                con2.setAutoCommit(true);
            }
            break;
        }
        default:
            break;
        }
    }

    public static void verifyAntiDependencyCycle(boolean tx1Committed, boolean tx2Committed, String values,
            String tableName) {
        // If both committed under SERIALIZABLE and state reached [1], [1], an acyclicity cycle was violated!
        if (tx1Committed && tx2Committed && values != null && values.contains("[1], [1]")) {
            throw new AssertionError(String.format(
                    "TxCheck Serializability Violation (Anti-Dependency Cycle T1 ->rw T2 ->rw T1)!\n"
                            + "Table: %s\nBoth T1 and T2 committed without serialization failure!\nResult values: %s",
                    tableName, values));
        }
    }

    public static void verifyCausalReadSkew(List<String> rY, List<String> rX, String tableName) {
        // If y is read as 1, x must not be 0 in a causally consistent snapshot
        if (!rY.isEmpty() && rY.get(0).contains("1") && !rX.isEmpty() && rX.get(0).contains("0")) {
            throw new AssertionError(String.format(
                    "TxCheck Causal Consistency Violation (Read y=1 but stale x=0)!\nTable: %s\nRead Y: %s, Read X: %s",
                    tableName, rY, rX));
        }
    }

    private static List<String> executeAndGetRows(Statement statement, String sql) throws SQLException {
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

    private static void copyCurrentSchema(Connection source, Connection target) throws SQLException {
        String schema;
        try (Statement statement = source.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT current_schema()")) {
            schema = resultSet.next() ? resultSet.getString(1) : null;
        }
        if (schema != null && !schema.isEmpty()) {
            try (Statement statement = target.createStatement()) {
                statement.execute("SET search_path TO \"" + schema.replace("\"", "\"\"") + "\"");
            }
        }
    }

    @Override
    public Reproducer<GaussDBGlobalState> getLastReproducer() {
        return reproducer;
    }

}
