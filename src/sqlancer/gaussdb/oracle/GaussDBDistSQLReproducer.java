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
import sqlancer.gaussdb.oracle.GaussDBDistSQLOracle.DistSQLBundle;

public final class GaussDBDistSQLReproducer implements Reproducer<GaussDBGlobalState> {

    private final DistSQLBundle bundle;

    public GaussDBDistSQLReproducer(DistSQLBundle bundle) {
        this.bundle = bundle;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            // 1. Execute Centralized Plan
            GaussDBDistSQLOracle.resetDistSQLGucs(statement);
            for (String guc : bundle.getCentralizedGucs()) {
                statement.execute(guc);
            }
            List<String> centralizedRows = executeAndGetRows(statement, bundle.getQuery());

            // 2. Execute Distributed Stream Plan
            GaussDBDistSQLOracle.resetDistSQLGucs(statement);
            for (String guc : bundle.getDistributedGucs()) {
                statement.execute(guc);
            }
            List<String> distributedRows = executeAndGetRows(statement, bundle.getQuery());
            GaussDBDistSQLOracle.resetDistSQLGucs(statement);

            Map<String, Integer> m1 = toCountMap(centralizedRows);
            Map<String, Integer> m2 = toCountMap(distributedRows);
            return !m1.equals(m2);
        } catch (Exception e) {
            return true;
        }
    }

    public DistSQLBundle getBundle() {
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
