package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;

public final class GaussDBARGReproducer implements Reproducer<GaussDBGlobalState> {

    private final String query;
    private final String offendingRule;
    private final List<String> activeRules;

    public GaussDBARGReproducer(String query, String offendingRule, List<String> activeRules) {
        this.query = query;
        this.offendingRule = offendingRule;
        this.activeRules = activeRules;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            try {
                statement.execute("SET rewrite_rule = 'none'");
                List<String> baselineResults = executeAndGetRows(statement, query);

                String testRules = offendingRule != null ? offendingRule : String.join(", ", activeRules);
                statement.execute("SET rewrite_rule = '" + testRules + "'");
                List<String> variantResults = executeAndGetRows(statement, query);

                return !areEquivalent(baselineResults, variantResults);
            } finally {
                try {
                    statement.execute("RESET rewrite_rule");
                } catch (SQLException ignored) {
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    public String getQuery() {
        return query;
    }

    public String getOffendingRule() {
        return offendingRule;
    }

    public List<String> getActiveRules() {
        return activeRules;
    }

    private static boolean areEquivalent(List<String> list1, List<String> list2) {
        if (list1.size() != list2.size()) {
            return false;
        }
        List<String> sorted1 = new ArrayList<>(list1);
        List<String> sorted2 = new ArrayList<>(list2);
        sorted1.sort(Comparator.naturalOrder());
        sorted2.sort(Comparator.naturalOrder());
        return sorted1.equals(sorted2);
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
