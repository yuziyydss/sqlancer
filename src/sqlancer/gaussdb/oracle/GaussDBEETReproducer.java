package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;

public final class GaussDBEETReproducer implements Reproducer<GaussDBGlobalState> {

    private final String originalQuery;
    private final String transformedQuery;
    private final boolean ordered;

    public GaussDBEETReproducer(String originalQuery, String transformedQuery) {
        this(originalQuery, transformedQuery, false);
    }

    public GaussDBEETReproducer(String originalQuery, String transformedQuery, boolean ordered) {
        this.originalQuery = originalQuery;
        this.transformedQuery = transformedQuery;
        this.ordered = ordered;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            List<String> originalResults = executeAndGetRows(statement, originalQuery);
            List<String> transformedResults = executeAndGetRows(statement, transformedQuery);
            if (ordered) {
                return !originalResults.equals(transformedResults);
            }
            return !sorted(originalResults).equals(sorted(transformedResults));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isOrdered() {
        return ordered;
    }

    public String getOriginalQuery() {
        return originalQuery;
    }

    public String getTransformedQuery() {
        return transformedQuery;
    }

    private List<String> executeAndGetRows(Statement statement, String query) throws SQLException {
        List<String> results = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery(query)) {
            while (resultSet.next()) {
                int columnCount = resultSet.getMetaData().getColumnCount();
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.add(GaussDBEETOracle.normalizeCellValue(resultSet.getString(i)));
                }
                results.add(row.toString());
            }
        }
        return results;
    }

    private List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
        return copy;
    }

}
