package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.oracle.GaussDBThanosOracle.EngineQueryPair;

public final class GaussDBThanosReproducer implements Reproducer<GaussDBGlobalState> {

    private final EngineQueryPair pair;

    public GaussDBThanosReproducer(EngineQueryPair pair) {
        this.pair = pair;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            try {
                for (String sql : pair.getSetupSql()) {
                    statement.execute(sql);
                }
                List<String> astoreResults = executeAndGetRows(statement, pair.getAstoreQuery());
                List<String> ustoreResults = executeAndGetRows(statement, pair.getUstoreQuery());
                return !GaussDBThanosOracle.areEquivalent(astoreResults, ustoreResults);
            } finally {
                for (String sql : pair.getCleanupSql()) {
                    try {
                        statement.execute(sql);
                    } catch (SQLException ignored) {
                        // Best-effort cleanup.
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    public EngineQueryPair getPair() {
        return pair;
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

}
