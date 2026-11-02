package sqlancer.gaussdb.oracle;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.oracle.GaussDBTLPOracle.TLPQueryPair;

public class GaussDBTLPReproducer implements Reproducer<GaussDBGlobalState> {

    private final TLPQueryPair pair;

    public GaussDBTLPReproducer(TLPQueryPair pair) {
        this.pair = pair;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            List<String> originalRows = GaussDBTLPOracle.executeAndGetRows(statement, pair.getOriginalQuery(), null);
            List<String> partitionedRows = GaussDBTLPOracle.executeAndGetRows(statement, pair.getPartitionedQuery(),
                    null);
            return !GaussDBTLPOracle.areEquivalent(originalRows, partitionedRows);
        } catch (SQLException ignored) {
            return false;
        }
    }

}
