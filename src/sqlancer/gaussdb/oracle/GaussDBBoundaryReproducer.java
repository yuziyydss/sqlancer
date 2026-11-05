package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.oracle.GaussDBBoundaryOracle.BoundaryBundle;

public final class GaussDBBoundaryReproducer implements Reproducer<GaussDBGlobalState> {

    private final BoundaryBundle bundle;

    public GaussDBBoundaryReproducer(BoundaryBundle bundle) {
        this.bundle = bundle;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            List<String> directRows = executeAndGetRows(statement, bundle.getDirectQuery());
            List<String> cteRows = executeAndGetRows(statement, bundle.getCteQuery());
            if (!GaussDBBoundaryOracle.areEquivalent(directRows, cteRows)) {
                return true;
            }
            List<String> subqueryRows = executeAndGetRows(statement, bundle.getSubqueryQuery());
            return !GaussDBBoundaryOracle.areEquivalent(directRows, subqueryRows);
        } catch (Exception e) {
            return true;
        }
    }

    public BoundaryBundle getBundle() {
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
