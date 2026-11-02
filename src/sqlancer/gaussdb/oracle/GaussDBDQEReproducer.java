package sqlancer.gaussdb.oracle;

import java.sql.SQLException;
import java.sql.Statement;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.oracle.GaussDBDQEOracle.DQEQueryPlan;

public class GaussDBDQEReproducer implements Reproducer<GaussDBGlobalState> {

    private final DQEQueryPlan plan;

    public GaussDBDQEReproducer(DQEQueryPlan plan) {
        this.plan = plan;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            try {
                return !GaussDBDQEOracle.runAndCheck(statement, plan, null);
            } finally {
                try {
                    statement.execute(plan.getCleanupSql());
                } catch (SQLException ignored) {
                    // Best-effort cleanup.
                }
            }
        } catch (SQLException ignored) {
            return false;
        }
    }

}
