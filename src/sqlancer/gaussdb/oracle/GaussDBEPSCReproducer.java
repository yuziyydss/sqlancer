package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.oracle.GaussDBEPSCOracle.PreparedQueryPlan;

public final class GaussDBEPSCReproducer implements Reproducer<GaussDBGlobalState> {

    private final PreparedQueryPlan plan;
    private final String planCacheMode;

    public GaussDBEPSCReproducer(PreparedQueryPlan plan, String planCacheMode) {
        this.plan = plan;
        this.planCacheMode = planCacheMode;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            quietlyDeallocate(statement);
            try {
                List<String> directResults = executeAndGetRows(statement, plan.getOriginalQuery());
                statement.execute(plan.getPrepareSql());
                if (planCacheMode != null && !planCacheMode.isEmpty()) {
                    statement.execute("SET plan_cache_mode = " + planCacheMode);
                }
                int runs = "auto".equals(planCacheMode) ? 6 : 1;
                for (int i = 0; i < runs; i++) {
                    List<String> preparedResults = executeAndGetRows(statement, plan.getExecuteSql());
                    if (!GaussDBEPSCOracle.areEquivalent(directResults, preparedResults)) {
                        return true;
                    }
                }
                return false;
            } finally {
                quietlyResetPlanCacheMode(statement);
                quietlyDeallocate(statement);
            }
        } catch (Exception e) {
            return false;
        }
    }

    public PreparedQueryPlan getPlan() {
        return plan;
    }

    public String getPlanCacheMode() {
        return planCacheMode;
    }

    private List<String> executeAndGetRows(Statement statement, String query) throws SQLException {
        List<String> results = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery(query)) {
            int columnCount = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.add(resultSet.getString(i));
                }
                results.add(row.toString());
            }
        }
        return results;
    }

    private void quietlyDeallocate(Statement statement) {
        try {
            statement.execute(plan.getDeallocateSql());
        } catch (SQLException ignored) {
            // Ignore when the statement was not prepared yet.
        }
    }

    private void quietlyResetPlanCacheMode(Statement statement) {
        try {
            statement.execute("SET plan_cache_mode = auto");
        } catch (SQLException ignored) {
            // Best-effort reset.
        }
    }

}
