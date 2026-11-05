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
import sqlancer.gaussdb.oracle.GaussDBSQLaserOracle.SQLaserBundle;

public final class GaussDBSQLaserReproducer implements Reproducer<GaussDBGlobalState> {

    private final SQLaserBundle bundle;

    public GaussDBSQLaserReproducer(SQLaserBundle bundle) {
        this.bundle = bundle;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            List<String> r1 = executeAndGetRows(statement, bundle.getQuery1());
            List<String> r2 = executeAndGetRows(statement, bundle.getQuery2());
            Set<String> s1 = new HashSet<>(r1);
            Set<String> s2 = new HashSet<>(r2);
            return !s1.equals(s2);
        } catch (Exception e) {
            return true;
        }
    }

    public SQLaserBundle getBundle() {
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
