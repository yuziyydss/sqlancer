package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.oracle.GaussDBPLSQLOracle.PLSQLBundle;

public final class GaussDBPLSQLReproducer implements Reproducer<GaussDBGlobalState> {

    private final PLSQLBundle bundle;

    public GaussDBPLSQLReproducer(PLSQLBundle bundle) {
        this.bundle = bundle;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            List<String> declRows = executeAndGetRows(statement, bundle.getDeclarativeSql());
            statement.execute(bundle.getCreateFunctionSql());
            try {
                List<String> plsqlRows = executeAndGetRows(statement, bundle.getCallFunctionSql());
                return !GaussDBPLSQLOracle.areEquivalent(declRows, plsqlRows);
            } finally {
                quietlyDropFunction(statement, bundle.getDropFunctionSql());
            }
        } catch (Exception e) {
            return true;
        }
    }

    public PLSQLBundle getBundle() {
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

    private void quietlyDropFunction(Statement statement, String dropSql) {
        try {
            statement.execute(dropSql);
        } catch (SQLException ignored) {
            // Ignore transient cleanup errors
        }
    }

}
