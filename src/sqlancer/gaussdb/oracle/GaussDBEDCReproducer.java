package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;

public final class GaussDBEDCReproducer implements Reproducer<GaussDBGlobalState> {

    private final List<String> setupSql;
    private final String baseQuery;
    private final String shiftedQuery;
    private final List<String> cleanupSql;

    public GaussDBEDCReproducer(List<String> setupSql, String baseQuery, String shiftedQuery, List<String> cleanupSql) {
        this.setupSql = Collections.unmodifiableList(new ArrayList<>(setupSql));
        this.baseQuery = baseQuery;
        this.shiftedQuery = shiftedQuery;
        this.cleanupSql = Collections.unmodifiableList(new ArrayList<>(cleanupSql));
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            for (String sql : setupSql) {
                try {
                    statement.execute(sql);
                } catch (SQLException ignored) {
                }
            }

            List<String> baseRows = executeAndFetch(statement, baseQuery);
            List<String> shiftedRows = executeAndFetch(statement, shiftedQuery);

            return !baseRows.equals(shiftedRows);
        } catch (Exception e) {
            return false;
        } finally {
            try (Statement cleanupStatement = globalState.getConnection().createStatement()) {
                for (String sql : cleanupSql) {
                    try {
                        cleanupStatement.execute(sql);
                    } catch (SQLException ignored) {
                    }
                }
            } catch (SQLException ignored) {
            }
        }
    }

    private static List<String> executeAndFetch(Statement statement, String query) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(query)) {
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        row.append(", ");
                    }
                    row.append(String.valueOf(rs.getObject(i)));
                }
                rows.add(row.toString());
            }
        }
        rows.sort(Comparator.naturalOrder());
        return rows;
    }

    public List<String> getSetupSql() {
        return setupSql;
    }

    public String getBaseQuery() {
        return baseQuery;
    }

    public String getShiftedQuery() {
        return shiftedQuery;
    }

    public List<String> getCleanupSql() {
        return cleanupSql;
    }
}
