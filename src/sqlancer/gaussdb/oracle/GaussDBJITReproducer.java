package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.oracle.GaussDBJITOracle.JITVariant;

public final class GaussDBJITReproducer implements Reproducer<GaussDBGlobalState> {

    private final String query;
    private final JITVariant variant;

    public GaussDBJITReproducer(String query, JITVariant variant) {
        this.query = query;
        this.variant = variant;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            List<String> interpretedRows;
            statement.execute("SET jit = off");
            try {
                interpretedRows = executeAndGetRows(statement, query);
            } finally {
                quietlyResetJIT(statement);
            }

            for (String guc : variant.getGucSettings()) {
                statement.execute(guc);
            }
            try {
                List<String> jitRows = executeAndGetRows(statement, query);
                return !GaussDBJITOracle.areEquivalent(interpretedRows, jitRows);
            } catch (SQLException e) {
                return true;
            } finally {
                quietlyResetJIT(statement);
            }
        } catch (Exception e) {
            return false;
        }
    }

    public String getQuery() {
        return query;
    }

    public JITVariant getVariant() {
        return variant;
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

    private void quietlyResetJIT(Statement statement) {
        for (String reset : GaussDBJITOracle.RESET_JIT_GUCS) {
            try {
                statement.execute(reset);
            } catch (SQLException ignored) {
                // Ignore transient reset error
            }
        }
    }

}
