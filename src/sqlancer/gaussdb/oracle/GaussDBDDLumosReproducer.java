package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.oracle.GaussDBDDLumosOracle.DDLumosBundle;

public final class GaussDBDDLumosReproducer implements Reproducer<GaussDBGlobalState> {

    private final DDLumosBundle bundle;

    public GaussDBDDLumosReproducer(DDLumosBundle bundle) {
        this.bundle = bundle;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            List<String> snapshot = executeAndGetRows(statement, bundle.getSnapshotQuery());

            statement.execute("BEGIN;");
            for (String ddl : bundle.getDdlOperations()) {
                try {
                    statement.execute(ddl);
                } catch (SQLException ignored) {
                }
            }
            statement.execute("ROLLBACK;");

            List<String> postRollback = executeAndGetRows(statement, bundle.getSnapshotQuery());
            return !snapshot.equals(postRollback);
        } catch (Exception e) {
            return true;
        }
    }

    public DDLumosBundle getBundle() {
        return bundle;
    }

    private List<String> executeAndGetRows(Statement statement, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>(cols);
                for (int i = 1; i <= cols; i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

}
