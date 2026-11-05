package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.oracle.GaussDBValScopeOracle.ValScopeBundle;

public final class GaussDBValScopeReproducer implements Reproducer<GaussDBGlobalState> {

    private final ValScopeBundle bundle;

    public GaussDBValScopeReproducer(ValScopeBundle bundle) {
        this.bundle = bundle;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            List<String> baseRows = executeAndGetRows(statement, bundle.getBaseQuery());
            List<String> pertRows = executeAndGetRows(statement, bundle.getPerturbedQuery());
            switch (bundle.getMode()) {
            case AGGREGATE_PERTURBATION:
                try {
                    GaussDBValScopeOracle.verifyAggregatePerturbation(bundle, baseRows, pertRows);
                    return false;
                } catch (AssertionError e) {
                    return true;
                }
            case WINDOW_MONOTONICITY:
            case SCALAR_ROUNDTRIP:
                return !GaussDBValScopeOracle.areEquivalent(baseRows, pertRows);
            default:
                return false;
            }
        } catch (Exception e) {
            return true;
        }
    }

    public ValScopeBundle getBundle() {
        return bundle;
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

}
