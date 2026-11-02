package sqlancer.gaussdb.oracle;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.oracle.GaussDBDQPOracle.PlanVariant;

public class GaussDBDQPReproducer implements Reproducer<GaussDBGlobalState> {

    private final String baseQuery;
    private final PlanVariant variant;

    public GaussDBDQPReproducer(String baseQuery, PlanVariant variant) {
        this.baseQuery = baseQuery;
        this.variant = variant;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            try {
                GaussDBDQPOracle.applyGucs(statement, GaussDBDQPOracle.RESET_GUCS);
                List<String> baseRows = GaussDBDQPOracle.executeAndGetRows(statement, baseQuery, null);
                GaussDBDQPOracle.applyGucs(statement, variant.getGucSettings());
                List<String> variantRows = GaussDBDQPOracle.executeAndGetRows(statement, variant.getSql(), null);
                return !GaussDBDQPOracle.areEquivalent(baseRows, variantRows);
            } finally {
                GaussDBDQPOracle.quietlyResetGucs(statement);
            }
        } catch (SQLException ignored) {
            return false;
        }
    }

}
