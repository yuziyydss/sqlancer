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
 * Fucci (VLDB '25) Conflict-Invariant-Guided Transaction Oracle for GaussDB.
 *
 * <p>
 * Tests fine-grained conflict invariants under stepped synchronization barriers:
 * 1. LOCK_COMMIT_INVARIANT: T1 acquires an exclusive row update lock. T2 attempts conflicting update.
 *    When T1 commits, T2 must observe T1's committed version rather than stale state.
 * 2. LOCK_ROLLBACK_INVARIANT: T1 locks and updates row, then executes ROLLBACK. T2 must resume and update
 *    cleanly from the pre-T1 base version without uncommitted dirty read leakage.
 *
 * Exposes bugs in row-level lock release, lock wait queues, and rollback undo chain traversal in Ustore/Astore.
 * </p>
 */
public class GaussDBFucciOracle implements TestOracle<GaussDBGlobalState> {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBFucciReproducer reproducer;

    public GaussDBFucciOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
        errors.add("could not serialize access");
        errors.add("deadlock detected");
        errors.add("lock wait timeout");
        errors.add("concurrent update");
        errors.add("canceling statement due to statement timeout");
    }

    public enum FucciMode {
        LOCK_COMMIT_INVARIANT,
        LOCK_ROLLBACK_INVARIANT
    }

    public static final class FucciBundle {

        private final FucciMode mode;
        private final String tableName;
        private final String setupSql;
        private final String cleanupSql;

        public FucciBundle(FucciMode mode, String tableName, String setupSql, String cleanupSql) {
            this.mode = mode;
            this.tableName = tableName;
            this.setupSql = setupSql;
            this.cleanupSql = cleanupSql;
        }

        public FucciMode getMode() {
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
        FucciMode mode = Randomly.fromOptions(FucciMode.values());
        long id = COUNTER.incrementAndGet();
        String tableName = "t_fucci_" + id;

        String setupSql = String.format(
                "DROP TABLE IF EXISTS %s CASCADE; CREATE TABLE %s (id INT PRIMARY KEY, val INT, version INT); "
                        + "INSERT INTO %s VALUES (1, 100, 1);",
                tableName, tableName, tableName);
        String cleanupSql = "DROP TABLE IF EXISTS " + tableName + " CASCADE;";

        FucciBundle bundle = new FucciBundle(mode, tableName, setupSql, cleanupSql);
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
                runFucciSchedule(bundle, con1, con2);
            } catch (AssertionError e) {
                reproducer = new GaussDBFucciReproducer(bundle);
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

    public static void runFucciSchedule(FucciBundle bundle, Connection con1, Connection con2) throws SQLException {
        String t = bundle.getTableName();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch t1LockAcquired = new CountDownLatch(1);
        CountDownLatch t2BlockedOrStarted = new CountDownLatch(1);
        AtomicBoolean t2Succeeded = new AtomicBoolean(false);
        AtomicReference<String> finalState = new AtomicReference<>();

        executor.submit(() -> {
            try {
                con1.setAutoCommit(false);
                try (Statement s1 = con1.createStatement()) {
                    s1.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED;");
                    // T1 acquires row-level lock
                    s1.execute("UPDATE " + t + " SET val = 150, version = version + 1 WHERE id = 1;");
                    t1LockAcquired.countDown();
                    t2BlockedOrStarted.await(1, TimeUnit.SECONDS);

                    if (bundle.getMode() == FucciMode.LOCK_COMMIT_INVARIANT) {
                        con1.commit();
                    } else {
                        con1.rollback();
                    }
                }
            } catch (Exception ignored) {
                try {
                    con1.rollback();
                } catch (SQLException ignoredRollback) {
                }
            }
        });

        executor.submit(() -> {
            try {
                t1LockAcquired.await(3, TimeUnit.SECONDS);
                t2BlockedOrStarted.countDown();
                con2.setAutoCommit(false);
                try (Statement s2 = con2.createStatement()) {
                    s2.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED;");
                    // T2 attempts conflicting update on row 1
                    s2.execute("UPDATE " + t + " SET val = 200, version = version + 1 WHERE id = 1;");
                }
                con2.commit();
                t2Succeeded.set(true);
            } catch (Exception ignored) {
                try {
                    con2.rollback();
                } catch (SQLException ignoredRollback) {
                }
            }
        });

        try {
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
            List<String> rows = executeAndGetRows(checkStmt, "SELECT val, version FROM " + t + " WHERE id = 1");
            finalState.set(rows.isEmpty() ? "" : rows.get(0));
        }

        verifyFucciInvariants(bundle, t2Succeeded.get(), finalState.get(), t);
    }

    public static void verifyFucciInvariants(FucciBundle bundle, boolean t2Succeeded, String stateRow, String tableName) {
        if (!t2Succeeded) {
            // T2 might encounter lock timeout or serialization conflict, which is sound
            return;
        }

        if (stateRow == null || stateRow.isEmpty()) {
            return;
        }

        String clean = stateRow.replaceAll("[\\[\\] ]", "");
        String[] parts = clean.split(",");
        if (parts.length < 2) {
            return;
        }

        try {
            long val = Long.parseLong(parts[0]);
            long ver = Long.parseLong(parts[1]);

            if (bundle.getMode() == FucciMode.LOCK_ROLLBACK_INVARIANT) {
                // T1 rolled back. T2 updated on clean initial state (val=100, version=1).
                // Expecting val=200, version=2. If version=3 or val!=200, uncommitted dirty read leaked!
                if (val != 200 || ver != 2) {
                    throw new AssertionError(String.format(
                            "Fucci Conflict Invariant Violation (Dirty state after Rollback)!\n"
                                    + "Table: %s\nMode: %s\nExpected val=200, version=2, but observed: val=%d, ver=%d",
                            tableName, bundle.getMode(), val, ver));
                }
            } else if (bundle.getMode() == FucciMode.LOCK_COMMIT_INVARIANT) {
                // T1 committed (val=150, version=2). T2 updated on top (val=200, version=3).
                if (val != 200 || ver != 3) {
                    throw new AssertionError(String.format(
                            "Fucci Conflict Invariant Violation (Lost update after Commit)!\n"
                                    + "Table: %s\nMode: %s\nExpected val=200, version=3, but observed: val=%d, ver=%d",
                            tableName, bundle.getMode(), val, ver));
                }
            }
        } catch (NumberFormatException ignored) {
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
