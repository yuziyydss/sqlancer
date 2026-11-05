package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.oracle.GaussDBIVMOracle.IVMBundle;

public final class GaussDBIVMReproducer implements Reproducer<GaussDBGlobalState> {

    private final IVMBundle bundle;

    public GaussDBIVMReproducer(IVMBundle bundle) {
        this.bundle = bundle;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            statement.execute(bundle.getCreateMvSql());

            for (String dml : bundle.getDmlMutations()) {
                try {
                    statement.execute(dml);
                } catch (SQLException ignored) {
                }
            }

            statement.execute(bundle.getRefreshSql());

            List<String> mvRows = executeAndGetRows(statement, bundle.getMvQuery());
            List<String> baseRows = executeAndGetRows(statement, bundle.getBaseQuery());

            statement.execute(bundle.getCleanupSql());

            Map<String, Integer> m1 = toCountMap(mvRows);
            Map<String, Integer> m2 = toCountMap(baseRows);
            return !m1.equals(m2);
        } catch (Exception e) {
            return true;
        }
    }

    public IVMBundle getBundle() {
        return bundle;
    }

    private static Map<String, Integer> toCountMap(List<String> list) {
        Map<String, Integer> map = new HashMap<>();
        for (String item : list) {
            map.put(item, map.getOrDefault(item, 0) + 1);
        }
        return map;
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
