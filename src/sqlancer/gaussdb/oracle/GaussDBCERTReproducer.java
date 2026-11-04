package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;

public final class GaussDBCERTReproducer implements Reproducer<GaussDBGlobalState> {

    private static final Pattern ROWS_PATTERN = Pattern.compile("rows=([0-9]+)");

    private final String query1;
    private final String query2;
    private final long estimated1;
    private final long estimated2;

    public GaussDBCERTReproducer(String query1, String query2, long estimated1, long estimated2) {
        this.query1 = query1;
        this.query2 = query2;
        this.estimated1 = estimated1;
        this.estimated2 = estimated2;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            Long e1 = extractEstimatedRows(statement, query1);
            Long e2 = extractEstimatedRows(statement, query2);
            if (e1 != null && e2 != null) {
                return e2 > e1 + 1;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static Long extractEstimatedRows(Statement statement, String query) throws SQLException {
        try {
            statement.execute("SET explain_perf_mode = normal");
        } catch (SQLException ignored) {
        }
        try (ResultSet rs = statement.executeQuery("EXPLAIN " + query)) {
            while (rs.next()) {
                String line = rs.getString(1);
                Matcher matcher = ROWS_PATTERN.matcher(line);
                if (matcher.find()) {
                    return Long.parseLong(matcher.group(1));
                }
            }
        }
        return null;
    }

    public String getQuery1() {
        return query1;
    }

    public String getQuery2() {
        return query2;
    }

    public long getEstimated1() {
        return estimated1;
    }

    public long getEstimated2() {
        return estimated2;
    }
}
