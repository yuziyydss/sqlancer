package sqlancer.gaussdb.oracle;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import sqlancer.Reproducer;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.oracle.GaussDBTxCheckOracle.TxCheckBundle;

public final class GaussDBTxCheckReproducer implements Reproducer<GaussDBGlobalState> {

    private final TxCheckBundle bundle;

    public GaussDBTxCheckReproducer(TxCheckBundle bundle) {
        this.bundle = bundle;
    }

    @Override
    public boolean bugStillTriggers(GaussDBGlobalState globalState) {
        try {
            Connection con1 = globalState.getConnection().getRawConnection();
            if (con1 == null) {
                return false;
            }
            String url = con1.getMetaData().getURL();
            String user = globalState.getOptions() != null ? globalState.getOptions().getUserName() : null;
            String pass = globalState.getOptions() != null ? globalState.getOptions().getPassword() : null;
            Connection con2 = (user != null && pass != null) ? DriverManager.getConnection(url, user, pass) : DriverManager.getConnection(url);

            try (Statement s1 = con1.createStatement(); Statement s2 = con2.createStatement()) {
                GaussDBTxCheckOracle.runTxCheckSchedule(bundle, con1, con2);
                return false;
            } catch (AssertionError e) {
                return true;
            } finally {
                if (con2 != null && !con2.isClosed()) {
                    con2.close();
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    public TxCheckBundle getBundle() {
        return bundle;
    }

    public String getPiscoSignature() {
        return GaussDBPiscoMinimizer.computeSignature("TXCHECK",
                java.util.Collections.singletonList(bundle.getSetupSql()));
    }

    public static List<String> executeAndGetRows(Statement statement, String sql) throws SQLException {
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
