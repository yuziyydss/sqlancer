package sqlancer.gaussdb.oracle;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.SQLConnection;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.gaussdb.GaussDBErrors;
import sqlancer.gaussdb.GaussDBGlobalState;

/**
 * Write-Specific Serializability (WSS, VLDB'25) concurrent transaction testing oracle for GaussDB.
 *
 * <p>
 * Tests transaction isolation guarantees (SERIALIZABLE and REPEATABLE READ) under concurrent execution.
 * Two concurrent worker threads execute non-commutative write-specific transaction schedules on shared rows.
 *
 * If both transactions successfully commit without concurrency conflict aborts, the final database state
 * MUST match one of the serial schedules: Tx1 -> Tx2 or Tx2 -> Tx1.
 *
 * Any divergence detects lost updates, dirty writes, or MVCC visibility anomalies in GaussDB's Ustore/Astore engines.
 * </p>
 */
public class GaussDBWSSOracle implements TestOracle<GaussDBGlobalState> {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBWSSReproducer reproducer;

    public GaussDBWSSOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
        errors.add("could not serialize access");
        errors.add("deadlock detected");
        errors.add("lock wait timeout");
        errors.add("concurrent update");
        errors.add("canceling statement due to statement timeout");
    }

    public static final class WSSBundle {
        private final String tableName;
        private final String setupSql;
        private final String isolationLevel;
        private final List<String> tx1Sql;
        private final List<String> tx2Sql;
        private final String checkSql;
        private final Set<String> validStates;
        private final String cleanupSql;

        public WSSBundle(String tableName, String setupSql, String isolationLevel,
                List<String> tx1Sql, List<String> tx2Sql, String checkSql,
                Set<String> validStates, String cleanupSql) {
            this.tableName = tableName;
            this.setupSql = setupSql;
            this.isolationLevel = isolationLevel;
            this.tx1Sql = tx1Sql;
            this.tx2Sql = tx2Sql;
            this.checkSql = checkSql;
            this.validStates = validStates;
            this.cleanupSql = cleanupSql;
        }

        public String getTableName() {
            return tableName;
        }

        public String getSetupSql() {
            return setupSql;
        }

        public String getIsolationLevel() {
            return isolationLevel;
        }

        public List<String> getTx1Sql() {
            return tx1Sql;
        }

        public List<String> getTx2Sql() {
            return tx2Sql;
        }

        public String getCheckSql() {
            return checkSql;
        }

        public Set<String> getValidStates() {
            return validStates;
        }

        public String getCleanupSql() {
            return cleanupSql;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        WSSBundle bundle = generateBundle();
        verifyBundle(bundle);
    }

    public static WSSBundle generateBundle() {
        long id = COUNTER.incrementAndGet();
        String tableName = "t_wss_" + id;

        String isolationLevel = Randomly.getBoolean() ? "SERIALIZABLE" : "REPEATABLE READ";

        String setupSql = String.format(
                "DROP TABLE IF EXISTS %s CASCADE; "
                + "CREATE TABLE %s (id INT PRIMARY KEY, balance INT, counter INT, note VARCHAR(50)); "
                + "INSERT INTO %s VALUES (1, 100, 0, 'INIT')",
                tableName, tableName, tableName);

        List<String> tx1Sql = Arrays.asList(
                String.format("UPDATE %s SET balance = balance + 50 WHERE id = 1", tableName),
                String.format("UPDATE %s SET counter = counter + 1 WHERE id = 1", tableName)
        );

        List<String> tx2Sql = Arrays.asList(
                String.format("UPDATE %s SET balance = balance * 2 WHERE id = 1", tableName),
                String.format("UPDATE %s SET counter = counter + 10 WHERE id = 1", tableName)
        );

        String checkSql = String.format("SELECT balance, counter FROM %s WHERE id = 1", tableName);

        // Serial Order 1: Tx1 -> Tx2 => (100 + 50) * 2 = 300, counter = 11
        // Serial Order 2: Tx2 -> Tx1 => (100 * 2) + 50 = 250, counter = 11
        Set<String> validStates = new HashSet<>(Arrays.asList("300:11", "250:11"));

        String cleanupSql = "DROP TABLE IF EXISTS " + tableName + " CASCADE";

        return new WSSBundle(tableName, setupSql, isolationLevel, tx1Sql, tx2Sql, checkSql, validStates, cleanupSql);
    }

    private void verifyBundle(WSSBundle bundle) throws SQLException {
        SQLConnection sqlConnection = state.getConnection();
        Connection con1 = sqlConnection.getRawConnection();
        if (con1 == null) {
            return;
        }

        Connection con2 = null;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Statement initStatement = con1.createStatement()) {
            initStatement.execute(bundle.getSetupSql());

            String url = con1.getMetaData().getURL();
            String user = state.getOptions() != null ? state.getOptions().getUserName() : null;
            String pass = state.getOptions() != null ? state.getOptions().getPassword() : null;
            if (user != null && pass != null) {
                con2 = DriverManager.getConnection(url, user, pass);
            } else {
                con2 = DriverManager.getConnection(url);
            }

            final Connection conn1 = con1;
            final Connection conn2 = con2;
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicBoolean tx1Committed = new AtomicBoolean(false);
            AtomicBoolean tx2Committed = new AtomicBoolean(false);

            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    conn1.setAutoCommit(false);
                    try (Statement s = conn1.createStatement()) {
                        s.execute("SET TRANSACTION ISOLATION LEVEL " + bundle.getIsolationLevel());
                        for (String sql : bundle.getTx1Sql()) {
                            s.execute(sql);
                        }
                    }
                    conn1.commit();
                    tx1Committed.set(true);
                } catch (Exception ignored) {
                    try {
                        conn1.rollback();
                    } catch (SQLException ignoredRollback) {
                    }
                }
            });

            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    conn2.setAutoCommit(false);
                    try (Statement s = conn2.createStatement()) {
                        s.execute("SET TRANSACTION ISOLATION LEVEL " + bundle.getIsolationLevel());
                        for (String sql : bundle.getTx2Sql()) {
                            s.execute(sql);
                        }
                    }
                    conn2.commit();
                    tx2Committed.set(true);
                } catch (Exception ignored) {
                    try {
                        conn2.rollback();
                    } catch (SQLException ignoredRollback) {
                    }
                }
            });

            try {
                readyLatch.await(5, TimeUnit.SECONDS);
                startLatch.countDown();
                executor.shutdown();
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IgnoreMeException();
            }

            // Invariant: If both transactions successfully committed under serializable/repeatable read,
            // the state must be serializable
            if (tx1Committed.get() && tx2Committed.get()) {
                conn1.setAutoCommit(true);
                try (Statement s = conn1.createStatement();
                        ResultSet rs = s.executeQuery(bundle.getCheckSql())) {
                    if (rs.next()) {
                        String finalState = rs.getString(1) + ":" + rs.getString(2);
                        if (!bundle.getValidStates().contains(finalState)) {
                            reproducer = new GaussDBWSSReproducer(bundle.getSetupSql(), bundle.getIsolationLevel(),
                                    bundle.getTx1Sql(), bundle.getTx2Sql(), bundle.getCheckSql(),
                                    bundle.getValidStates(), bundle.getCleanupSql());
                            state.getState().getLocalState().log(String.format(
                                    "WSS Serializability Anomaly detected! Final state: %s, Valid serial states: %s%nTable: %s",
                                    finalState, bundle.getValidStates(), bundle.getTableName()));
                            throw new AssertionError(String.format(
                                    "WSS Serializability Anomaly: Both transactions committed, but final state '%s' is not in valid serial states %s!",
                                    finalState, bundle.getValidStates()));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        } finally {
            executor.shutdownNow();
            if (con2 != null) {
                try {
                    con2.close();
                } catch (SQLException ignored) {
                }
            }
            try (Statement cleanupStatement = con1.createStatement()) {
                con1.setAutoCommit(true);
                cleanupStatement.execute(bundle.getCleanupSql());
            } catch (SQLException ignored) {
            }
        }
    }

    @Override
    public Reproducer<GaussDBGlobalState> getLastReproducer() {
        return reproducer;
    }
}
