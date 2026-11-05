package sqlancer.gaussdb.oracle;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;

public final class GaussDBWSSReproducer implements Reproducer<GaussDBGlobalState> {

    private final String setupSql;
    private final String isolationLevel;
    private final List<String> tx1Sql;
    private final List<String> tx2Sql;
    private final String checkSql;
    private final Set<String> validStates;
    private final String cleanupSql;

    public GaussDBWSSReproducer(String setupSql, String isolationLevel, List<String> tx1Sql, List<String> tx2Sql,
            String checkSql, Set<String> validStates, String cleanupSql) {
        this.setupSql = setupSql;
        this.isolationLevel = isolationLevel;
        this.tx1Sql = Collections.unmodifiableList(new ArrayList<>(tx1Sql));
        this.tx2Sql = Collections.unmodifiableList(new ArrayList<>(tx2Sql));
        this.checkSql = checkSql;
        this.validStates = Collections.unmodifiableSet(validStates);
        this.cleanupSql = cleanupSql;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        Connection con1 = globalState.getConnection().getRawConnection();
        if (con1 == null) {
            return false;
        }

        Connection con2 = null;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Statement initStatement = con1.createStatement()) {
            initStatement.execute(setupSql);

            String url = con1.getMetaData().getURL();
            String user = globalState.getOptions() != null ? globalState.getOptions().getUserName() : null;
            String pass = globalState.getOptions() != null ? globalState.getOptions().getPassword() : null;
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
                        s.execute("SET TRANSACTION ISOLATION LEVEL " + isolationLevel);
                        for (String sql : tx1Sql) {
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
                        s.execute("SET TRANSACTION ISOLATION LEVEL " + isolationLevel);
                        for (String sql : tx2Sql) {
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

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            // Bug only occurs when both transactions successfully commit with non-serializable interleaving
            if (tx1Committed.get() && tx2Committed.get()) {
                conn1.setAutoCommit(true);
                try (Statement s = conn1.createStatement();
                        ResultSet rs = s.executeQuery(checkSql)) {
                    if (rs.next()) {
                        String finalState = rs.getString(1) + ":" + rs.getString(2);
                        return !validStates.contains(finalState);
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
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
                cleanupStatement.execute(cleanupSql);
            } catch (SQLException ignored) {
            }
        }
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

    public String getPiscoSignature() {
        List<String> trace = new ArrayList<>();
        trace.add(setupSql);
        trace.addAll(tx1Sql);
        trace.addAll(tx2Sql);
        trace.add(checkSql);
        return GaussDBPiscoMinimizer.computeSignature("WSS_" + isolationLevel, trace);
    }
}
