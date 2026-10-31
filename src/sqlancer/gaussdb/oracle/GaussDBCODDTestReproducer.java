package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;

public final class GaussDBCODDTestReproducer implements Reproducer<GaussDBGlobalState> {

    private final String auxiliaryQuery;
    private final String originalQuery;
    private final String foldedQuery;

    public GaussDBCODDTestReproducer(String auxiliaryQuery, String originalQuery, String foldedQuery) {
        this.auxiliaryQuery = auxiliaryQuery;
        this.originalQuery = originalQuery;
        this.foldedQuery = foldedQuery;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            List<String> originalResults = executeAndGetRows(statement, originalQuery);
            List<String> foldedResults = executeAndGetRows(statement, foldedQuery);
            return !sorted(originalResults).equals(sorted(foldedResults));
        } catch (Exception e) {
            return false;
        }
    }

    public String getAuxiliaryQuery() {
        return auxiliaryQuery;
    }

    public String getOriginalQuery() {
        return originalQuery;
    }

    public String getFoldedQuery() {
        return foldedQuery;
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

    private List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy;
    }

}
