package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.oracle.GaussDBSCorOracle.SCorBundle;

public final class GaussDBSCorReproducer implements Reproducer<GaussDBGlobalState> {

    private final SCorBundle bundle;

    public GaussDBSCorReproducer(SCorBundle bundle) {
        this.bundle = bundle;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            List<String> r1 = executeAndGetRows(statement, bundle.getQuery1());
            List<String> r2 = executeAndGetRows(statement, bundle.getQuery2());
            try {
                GaussDBSCorOracle.verifySCorInvariants(bundle, r1, r2);
                return false;
            } catch (AssertionError e) {
                return true;
            }
        } catch (Exception e) {
            return true;
        }
    }

    public SCorBundle getBundle() {
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
