package sqlancer.gaussdb.oracle;

import java.sql.SQLException;
import java.sql.Statement;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.oracle.GaussDBNoRECOracle.NoRECQueryPair;

public class GaussDBNoRECReproducer implements Reproducer<GaussDBGlobalState> {

    private final NoRECQueryPair pair;

    public GaussDBNoRECReproducer(NoRECQueryPair pair) {
        this.pair = pair;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            long optimizedCount = GaussDBNoRECOracle.executeCount(statement, pair.getOptimizedQuery(), null);
            long unoptimizedCount = GaussDBNoRECOracle.executeCount(statement, pair.getUnoptimizedQuery(), null);
            return optimizedCount != unoptimizedCount;
        } catch (SQLException ignored) {
            return false;
        }
    }

}
