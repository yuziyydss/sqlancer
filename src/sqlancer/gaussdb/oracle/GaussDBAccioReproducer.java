package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.oracle.GaussDBAccioOracle.AccioBundle;
import sqlancer.gaussdb.oracle.GaussDBAccioOracle.AccioMode;

public final class GaussDBAccioReproducer implements Reproducer<GaussDBGlobalState> {

    private final AccioBundle bundle;

    public GaussDBAccioReproducer(AccioBundle bundle) {
        this.bundle = bundle;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            for (String sql : bundle.getSetupStatements()) {
                statement.execute(sql);
            }

            if (bundle.getMode() == AccioMode.ROW_LEVEL_SECURITY_ENFORCEMENT) {
                List<String> returnedLabels = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery(bundle.getTestQuery())) {
                    while (rs.next()) {
                        returnedLabels.add(rs.getString(1));
                    }
                }
                for (String label : returnedLabels) {
                    if (label == null || !bundle.getAllowedRLSLabel().equalsIgnoreCase(label.trim())) {
                        return true; // Still leaks secret rows
                    }
                }
                return false;
            } else {
                boolean accessSucceeded = false;
                try (ResultSet rs = statement.executeQuery(bundle.getTestQuery())) {
                    accessSucceeded = rs.next() || true;
                } catch (SQLException e) {
                    String msg = e.getMessage().toLowerCase();
                    if (msg.contains("permission denied") || msg.contains("denied")) {
                        accessSucceeded = false;
                    } else {
                        return false;
                    }
                }
                return accessSucceeded; // Still bypasses RBAC
            }
        } catch (Exception e) {
            return false;
        } finally {
            try (Statement cleanStmt = globalState.getConnection().createStatement()) {
                cleanStmt.execute(bundle.getCleanupSql());
            } catch (Exception ignored) {
            }
        }
    }

    public AccioBundle getBundle() {
        return bundle;
    }

    public String getPiscoSignature() {
        List<String> trace = new ArrayList<>(bundle.getSetupStatements());
        trace.add(bundle.getTestQuery());
        return GaussDBPiscoMinimizer.computeSignature("ACCIO_" + bundle.getMode(), trace);
    }
}
