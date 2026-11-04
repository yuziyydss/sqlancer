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

public final class GaussDBDDLCheckReproducer implements Reproducer<GaussDBGlobalState> {

    private final List<String> setupSql;
    private final String staticQuery;
    private final String evolvedQuery;
    private final List<String> cleanupSql;

    public GaussDBDDLCheckReproducer(List<String> setupSql, String staticQuery, String evolvedQuery,
            List<String> cleanupSql) {
        this.setupSql = Collections.unmodifiableList(new ArrayList<>(setupSql));
        this.staticQuery = staticQuery;
        this.evolvedQuery = evolvedQuery;
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

            List<String> staticRows = executeAndFetch(statement, staticQuery);
            List<String> evolvedRows = executeAndFetch(statement, evolvedQuery);

            return !staticRows.equals(evolvedRows);
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

    public String getStaticQuery() {
        return staticQuery;
    }

    public String getEvolvedQuery() {
        return evolvedQuery;
    }

    public List<String> getCleanupSql() {
        return cleanupSql;
    }
}
