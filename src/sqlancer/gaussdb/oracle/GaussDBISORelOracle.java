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
 * ISORel (ISSTA '25, ISCAS) Relation-Model-Aware Isolation Bug Oracle for GaussDB.
 *
 * <p>
 * Tests DBMS transaction isolation level invariants across REPEATABLE READ and SERIALIZABLE levels:
 * 1. NON_REPEATABLE_READ (P2): In REPEATABLE READ, a transaction reading a row must observe identical values
 *    even if a concurrent transaction modifies and commits that row.
 * 2. PHANTOM_READ (P3): In REPEATABLE READ / Snapshot Isolation, range/aggregate predicate counts must remain
 *    invariant against concurrent committed inserts.
 * 3. WRITE_SKEW (A5B): In SERIALIZABLE, concurrent transactions modifying disjoint keys with a shared constraint
 *    must either serialize or abort at least one transaction via 40001 serialization failure.
 *
 * Exposes MVCC snapshot leakage, phantom read leaks, and SSI predicate lock failure in Astore/Ustore.
 * </p>
 */
public class GaussDBISORelOracle implements TestOracle<GaussDBGlobalState> {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBISORelReproducer reproducer;

    public GaussDBISORelOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
        errors.add("could not serialize access");
        errors.add("deadlock detected");
        errors.add("lock wait timeout");
        errors.add("concurrent update");
    }

    public enum ISORelMode {
        NON_REPEATABLE_READ,
        PHANTOM_READ,
        WRITE_SKEW
    }

    public static final class ISORelBundle {

        private final ISORelMode mode;
        private final String tableName;
        private final String setupSql;
        private final String cleanupSql;

        public ISORelBundle(ISORelMode mode, String tableName, String setupSql, String cleanupSql) {
            this.mode = mode;
            this.tableName = tableName;
            this.setupSql = setupSql;
            this.cleanupSql = cleanupSql;
        }

        public ISORelMode getMode() {
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
        ISORelMode mode = Randomly.fromOptions(ISORelMode.values());
        long id = COUNTER.incrementAndGet();
        String tableName = "t_isorel_" + id;

        String setupSql;
        switch (mode) {
        case NON_REPEATABLE_READ:
            setupSql = String.format("DROP TABLE IF EXISTS %s CASCADE; CREATE TABLE %s (id INT PRIMARY KEY, val INT); "
                    + "INSERT INTO %s VALUES (1, 100);", tableName, tableName, tableName);
            break;
        case PHANTOM_READ:
            setupSql = String.format("DROP TABLE IF EXISTS %s CASCADE; CREATE TABLE %s (id INT PRIMARY KEY, grp INT, val INT); "
                    + "INSERT INTO %s VALUES (1, 10, 100), (2, 10, 200);", tableName, tableName, tableName);
            break;
        case WRITE_SKEW:
            setupSql = String.format("DROP TABLE IF EXISTS %s CASCADE; CREATE TABLE %s (id INT PRIMARY KEY, balance INT); "
                    + "INSERT INTO %s VALUES (1, 100), (2, 100);", tableName, tableName, tableName);
            break;
        default:
            throw new IgnoreMeException();
        }

        String cleanupSql = "DROP TABLE IF EXISTS " + tableName + " CASCADE;";
        ISORelBundle bundle = new ISORelBundle(mode, tableName, setupSql, cleanupSql);

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
            con2 = (user != null && pass != null) ? DriverManager.getConnection(url, user, pass) : DriverManager.getConnection(url);
            copyCurrentSchema(con1, con2);

            try {
                runISORelSchedule(bundle, con1, con2);
            } catch (AssertionError e) {
                reproducer = new GaussDBISORelReproducer(bundle);
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

    public static void runISORelSchedule(ISORelBundle bundle, Connection con1, Connection con2) throws SQLException {
        String t = bundle.getTableName();
        switch (bundle.getMode()) {
        case NON_REPEATABLE_READ: {
            // Tx1 reads v1. Tx2 updates and commits. Tx1 reads v2. Under RR, v1 must equal v2!
            con1.setAutoCommit(false);
            con2.setAutoCommit(false);
            try (Statement s1 = con1.createStatement(); Statement s2 = con2.createStatement()) {
                s1.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;");
                s2.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED;");

                List<String> r1 = executeAndGetRows(s1, "SELECT val FROM " + t + " WHERE id = 1");
                s2.execute("UPDATE " + t + " SET val = 999 WHERE id = 1;");
                con2.commit();

                List<String> r2 = executeAndGetRows(s1, "SELECT val FROM " + t + " WHERE id = 1");
                con1.commit();

                verifyNonRepeatableRead(r1, r2, t);
            } finally {
                con1.setAutoCommit(true);
                con2.setAutoCommit(true);
            }
            break;
        }
        case PHANTOM_READ: {
            // Tx1 counts group 10. Tx2 inserts into group 10 and commits. Tx1 counts again.
            // Under REPEATABLE READ / Snapshot Isolation, count1 must equal count2!
            con1.setAutoCommit(false);
            con2.setAutoCommit(false);
            try (Statement s1 = con1.createStatement(); Statement s2 = con2.createStatement()) {
                s1.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;");
                s2.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED;");

                List<String> r1 = executeAndGetRows(s1, "SELECT COUNT(*) FROM " + t + " WHERE grp = 10");
                s2.execute("INSERT INTO " + t + " VALUES (99, 10, 888);");
                con2.commit();

                List<String> r2 = executeAndGetRows(s1, "SELECT COUNT(*) FROM " + t + " WHERE grp = 10");
                con1.commit();

                verifyPhantomRead(r1, r2, t);
            } finally {
                con1.setAutoCommit(true);
                con2.setAutoCommit(true);
            }
            break;
        }
        case WRITE_SKEW: {
            // Two accounts balance = 100 each. Condition: balance_1 + balance_2 >= 100.
            // Tx1 subtracts 100 from account 1 if sum >= 100.
            // Tx2 subtracts 100 from account 2 if sum >= 100.
            // Under SERIALIZABLE, both committing to produce (0, 0) is a Write Skew violation!
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicBoolean tx1Committed = new AtomicBoolean(false);
            AtomicBoolean tx2Committed = new AtomicBoolean(false);
            AtomicReference<String> finalBalances = new AtomicReference<>();

            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    con1.setAutoCommit(false);
                    try (Statement s1 = con1.createStatement()) {
                        s1.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;");
                        List<String> rows = executeAndGetRows(s1, "SELECT SUM(balance) FROM " + t);
                        long sum = Long.parseLong(rows.get(0).replaceAll("[\\[\\] ]", ""));
                        if (sum >= 100) {
                            s1.execute("UPDATE " + t + " SET balance = balance - 100 WHERE id = 1;");
                        }
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
                readyLatch.countDown();
                try {
                    startLatch.await();
                    con2.setAutoCommit(false);
                    try (Statement s2 = con2.createStatement()) {
                        s2.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;");
                        List<String> rows = executeAndGetRows(s2, "SELECT SUM(balance) FROM " + t);
                        long sum = Long.parseLong(rows.get(0).replaceAll("[\\[\\] ]", ""));
                        if (sum >= 100) {
                            s2.execute("UPDATE " + t + " SET balance = balance - 100 WHERE id = 2;");
                        }
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
                readyLatch.await(5, TimeUnit.SECONDS);
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
                List<String> rows = executeAndGetRows(checkStmt, "SELECT balance FROM " + t + " ORDER BY id");
                finalBalances.set(rows.toString());
            }

            verifyWriteSkew(tx1Committed.get(), tx2Committed.get(), finalBalances.get(), t);
            break;
        }
        default:
            break;
        }
    }

    public static void verifyNonRepeatableRead(List<String> r1, List<String> r2, String tableName) {
        if (!r1.equals(r2)) {
            throw new AssertionError(String.format(
                    "ISORel Anomaly Violation (Non-Repeatable Read under REPEATABLE READ)!\n"
                            + "Table: %s\nFirst Read: %s\nSecond Read (after concurrent commit): %s",
                    tableName, r1, r2));
        }
    }

    public static void verifyPhantomRead(List<String> r1, List<String> r2, String tableName) {
        if (!r1.equals(r2)) {
            throw new AssertionError(String.format(
                    "ISORel Anomaly Violation (Phantom Read under REPEATABLE READ)!\n"
                            + "Table: %s\nFirst Count: %s\nSecond Count (after concurrent insert): %s",
                    tableName, r1, r2));
        }
    }

    public static void verifyWriteSkew(boolean tx1Committed, boolean tx2Committed, String balances, String tableName) {
        // If both committed under SERIALIZABLE and balance reached [0, 0] (violating total sum >= 100), Write Skew occurred!
        if (tx1Committed && tx2Committed && balances != null && balances.contains("[0], [0]")) {
            throw new AssertionError(String.format(
                    "ISORel Anomaly Violation (Write Skew under SERIALIZABLE)!\n"
                            + "Table: %s\nBoth Tx1 and Tx2 committed without serialization failure!\nBalances: %s",
                    tableName, balances));
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
