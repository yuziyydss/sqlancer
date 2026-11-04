package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;

public final class GaussDBERIQReproducer implements Reproducer<GaussDBGlobalState> {

    private final GaussDBERIQOracle.ERIQQueryBundle bundle;

    public GaussDBERIQReproducer(GaussDBERIQOracle.ERIQQueryBundle bundle) {
        this.bundle = bundle;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            try {
                List<String> derivedResults = executeAndGetRows(statement, bundle.getDerivedQuery());
                List<String> cteResults = executeAndGetRows(statement, bundle.getCteQuery());
                if (!GaussDBERIQOracle.areEquivalent(derivedResults, cteResults)) {
                    return true;
                }
                if (bundle.getViewSetup() != null) {
                    try {
                        statement.execute(bundle.getViewSetup());
                        List<String> viewResults = executeAndGetRows(statement, bundle.getViewQuery());
                        if (!GaussDBERIQOracle.areEquivalent(derivedResults, viewResults)) {
                            return true;
                        }
                    } finally {
                        if (bundle.getViewCleanup() != null) {
                            try {
                                statement.execute(bundle.getViewCleanup());
                            } catch (SQLException ignored) {
                            }
                        }
                    }
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public GaussDBERIQOracle.ERIQQueryBundle getBundle() {
        return bundle;
    }

    private List<String> executeAndGetRows(Statement statement, String query) throws SQLException {
        List<String> results = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery(query)) {
            int columnCount = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.add(GaussDBEETOracle.normalizeCellValue(resultSet.getString(i)));
                }
                results.add(row.toString());
            }
        }
        return results;
    }

}
