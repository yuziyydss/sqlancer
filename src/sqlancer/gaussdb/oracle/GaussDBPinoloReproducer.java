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
import sqlancer.gaussdb.oracle.GaussDBPinoloOracle.PinoloBundle;

public final class GaussDBPinoloReproducer implements Reproducer<GaussDBGlobalState> {

    private final PinoloBundle bundle;

    public GaussDBPinoloReproducer(PinoloBundle bundle) {
        this.bundle = bundle;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try (Statement statement = globalState.getConnection().createStatement()) {
            List<String> subRows = executeAndGetRows(statement, bundle.getSubQuery());
            List<String> baseRows = executeAndGetRows(statement, bundle.getBaseQuery());
            List<String> superRows = executeAndGetRows(statement, bundle.getSuperQuery());

            switch (bundle.getMode()) {
            case SET_SUBSUMPTION:
                Set<String> subSet = new HashSet<>(subRows);
                Set<String> baseSet = new HashSet<>(baseRows);
                Set<String> superSet = new HashSet<>(superRows);
                return !baseSet.containsAll(subSet) || !superSet.containsAll(baseSet);
            case COUNT_BOUND:
                if (subRows.isEmpty() || baseRows.isEmpty() || superRows.isEmpty()) {
                    return false;
                }
                long subCount = Long.parseLong(subRows.get(0).replaceAll("[\\[\\] ]", ""));
                long baseCount = Long.parseLong(baseRows.get(0).replaceAll("[\\[\\] ]", ""));
                long superCount = Long.parseLong(superRows.get(0).replaceAll("[\\[\\] ]", ""));
                return !(subCount <= baseCount && baseCount <= superCount);
            default:
                return false;
            }
        } catch (Exception e) {
            return true;
        }
    }

    public PinoloBundle getBundle() {
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
