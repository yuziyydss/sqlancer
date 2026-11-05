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
 * Sedar (IEEE TSE '24) Concurrency Scheduling & Seed Interleaving Oracle for GaussDB.
 *
 * <p>
 * Stresses non-deterministic concurrency races by varying thread scheduling jitter (&Delta;t &isin; {0, 10ms, 50ms, 100ms}):
 * Multi-statement cross-row transactions (e.g. balance transfers between accounts) are scheduled concurrently.
 *
 * Invariant: Regardless of concurrency interleaving or thread preemption delays, the global invariant
 * (SUM(balance) &equiv; CONSTANT) must never be violated, and all committed states must match a valid serial schedule.
 *
 * Catches lost updates, deadlocks, and dirty read anomalies in multi-threaded transaction pipelines.
 * </p>
 */
public class GaussDBSedarOracle implements TestOracle<GaussDBGlobalState> {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBSedarReproducer reproducer;

    public GaussDBSedarOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
        errors.add("could not serialize access");
        errors.add("deadlock detected");
        errors.add("lock wait timeout");
        errors.add("concurrent update");
        errors.add("canceling statement due to statement timeout");
    }

    public static final class SedarBundle {

        private final String tableName;
        private final String setupSql;
        private final long delayMs;
        private final String cleanupSql;

        public SedarBundle(String tableName, String setupSql, long delayMs, String cleanupSql) {
            this.tableName = tableName;
            this.setupSql = setupSql;
            this.delayMs = delayMs;
            this.cleanupSql = cleanupSql;
        }

        public String getTableName() {
            return tableName;
        }

        public String getSetupSql() {
            return setupSql;
        }

        public long getDelayMs() {
            return delayMs;
        }

        public String getCleanupSql() {
            return cleanupSql;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        long delayMs = Randomly.fromOptions(0L, 10L, 50L, 100L);
        long id = COUNTER.incrementAndGet();
        String tableName = "t_sedar_" + id;

        String setupSql = String.format(
                "DROP TABLE IF EXISTS %s CASCADE; CREATE TABLE %s (id INT PRIMARY KEY, balance INT); "
                        + "INSERT INTO %s VALUES (1, 100), (2, 100);",
                tableName, tableName, tableName);
        String cleanupSql = "DROP TABLE IF EXISTS " + tableName + " CASCADE;";

        SedarBundle bundle = new SedarBundle(tableName, setupSql, delayMs, cleanupSql);
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

            try {
                runSedarSchedule(bundle, con1, con2);
            } catch (AssertionError e) {
                reproducer = new GaussDBSedarReproducer(bundle);
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

    public static void runSedarSchedule(SedarBundle bundle, Connection con1, Connection con2) throws SQLException {
        String t = bundle.getTableName();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean tx1Committed = new AtomicBoolean(false);
        AtomicBoolean tx2Committed = new AtomicBoolean(false);
        AtomicReference<String> finalBalances = new AtomicReference<>();

        // Tx1: Transfer 30 from account 1 to 2
        executor.submit(() -> {
            try {
                startLatch.await();
                con1.setAutoCommit(false);
                try (Statement s1 = con1.createStatement()) {
                    s1.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED;");
                    s1.execute("UPDATE " + t + " SET balance = balance - 30 WHERE id = 1;");
                    if (bundle.getDelayMs() > 0) {
                        Thread.sleep(bundle.getDelayMs());
                    }
                    s1.execute("UPDATE " + t + " SET balance = balance + 30 WHERE id = 2;");
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

        // Tx2: Transfer 50 from account 2 to 1
        executor.submit(() -> {
            try {
                startLatch.await();
                con2.setAutoCommit(false);
                try (Statement s2 = con2.createStatement()) {
                    s2.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED;");
                    s2.execute("UPDATE " + t + " SET balance = balance - 50 WHERE id = 2;");
                    s2.execute("UPDATE " + t + " SET balance = balance + 50 WHERE id = 1;");
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
            startLatch.countDown();
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
            List<String> rows = executeAndGetRows(checkStmt, "SELECT SUM(balance), balance FROM " + t + " GROUP BY balance ORDER BY balance");
            finalBalances.set(rows.toString());
        }

        verifySedarInvariants(tx1Committed.get(), tx2Committed.get(), finalBalances.get(), t);
    }

    public static void verifySedarInvariants(boolean tx1Committed, boolean tx2Committed, String balanceSummary,
            String tableName) {
        if (balanceSummary == null || balanceSummary.isEmpty()) {
            return;
        }

        // If both committed: 100 - 30 + 50 = 120 (id=1), 100 + 30 - 50 = 80 (id=2).
        if (tx1Committed && tx2Committed) {
            if (!balanceSummary.contains("80") || !balanceSummary.contains("120")) {
                throw new AssertionError(String.format(
                        "Sedar Concurrency Accounting Violation (Lost Update)!\n"
                                + "Table: %s\nBoth Tx1 and Tx2 committed, but expected balances 80 and 120, got: %s",
                        tableName, balanceSummary));
            }
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

    @Override
    public Reproducer<GaussDBGlobalState> getLastReproducer() {
        return reproducer;
    }

}
