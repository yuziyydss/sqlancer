package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;

public final class GaussDBSRSReproducer implements Reproducer<GaussDBGlobalState> {

    public enum SRSType {
        INCLUSION,
        SEMI_ANTI_PARTITION,
        ANTI_JOIN_EQUIVALENCE
    }

    private final SRSType type;
    private final String query1;
    private final String query2;
    private final String query3;

    public GaussDBSRSReproducer(SRSType type, String query1, String query2, String query3) {
        this.type = type;
        this.query1 = query1;
        this.query2 = query2;
        this.query3 = query3;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            switch (type) {
            case INCLUSION: {
                Set<String> innerResults = new HashSet<>(executeAndGetRows(statement, query1));
                Set<String> leftResults = new HashSet<>(executeAndGetRows(statement, query2));
                return !leftResults.containsAll(innerResults);
            }
            case SEMI_ANTI_PARTITION: {
                Set<String> semiResults = new HashSet<>(executeAndGetRows(statement, query1));
                Set<String> antiResults = new HashSet<>(executeAndGetRows(statement, query2));
                Set<String> baseResults = new HashSet<>(executeAndGetRows(statement, query3));
                Set<String> intersection = new HashSet<>(semiResults);
                intersection.retainAll(antiResults);
                Set<String> union = new HashSet<>(semiResults);
                union.addAll(antiResults);
                return !intersection.isEmpty() || !union.equals(baseResults);
            }
            case ANTI_JOIN_EQUIVALENCE: {
                Set<String> notExistsResults = new HashSet<>(executeAndGetRows(statement, query1));
                Set<String> leftNullResults = new HashSet<>(executeAndGetRows(statement, query2));
                return !notExistsResults.equals(leftNullResults);
            }
            default:
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public SRSType getType() {
        return type;
    }

    public String getQuery1() {
        return query1;
    }

    public String getQuery2() {
        return query2;
    }

    public String getQuery3() {
        return query3;
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
