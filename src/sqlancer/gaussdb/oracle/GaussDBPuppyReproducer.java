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
import sqlancer.gaussdb.oracle.GaussDBPuppyOracle.PuppyBundle;

public final class GaussDBPuppyReproducer implements Reproducer<GaussDBGlobalState> {

    private final PuppyBundle bundle;

    public GaussDBPuppyReproducer(PuppyBundle bundle) {
        this.bundle = bundle;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            GaussDBPuppyOracle.resetPuppyGucs(statement);
            List<String> defaultRows = executeAndGetRows(statement, bundle.getQuery());

            for (String gucCommand : bundle.getGucCommands()) {
                statement.execute(gucCommand);
            }
            List<String> constrainedRows = executeAndGetRows(statement, bundle.getQuery());
            GaussDBPuppyOracle.resetPuppyGucs(statement);

            Set<String> s1 = new HashSet<>(defaultRows);
            Set<String> s2 = new HashSet<>(constrainedRows);
            return !s1.equals(s2);
        } catch (Exception e) {
            return true;
        }
    }

    public PuppyBundle getBundle() {
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
