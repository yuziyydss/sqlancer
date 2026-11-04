package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.SQLConnection;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.oracle.GaussDBDDLCheckOracle.DDLCheckBundle;

class GaussDBDDLCheckOracleTest {

    @Test
    void testBundleGeneration() {
        GaussDBColumn col0 = new GaussDBColumn("c0", GaussDBDataType.INT);
        GaussDBTable table = new GaussDBTable("t_base", Collections.singletonList(col0), Collections.emptyList(), false, true);

        DDLCheckBundle bundle = GaussDBDDLCheckOracle.generateBundle(table);
        assertNotNull(bundle);
        assertFalse(bundle.getSetupSql().isEmpty());
        assertTrue(bundle.getStaticQuery().startsWith("SELECT * FROM t_stat_"));
        assertTrue(bundle.getEvolvedQuery().startsWith("SELECT * FROM t_evol_"));
        assertFalse(bundle.getCleanupSql().isEmpty());
    }

    @Test
    void testReproducerDetectsDiscrepancy() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:gaussdb_ddlcheck_test")) {
            GaussDBGlobalState state = new GaussDBGlobalState();
            state.setConnection(new SQLConnection(connection));

            List<String> setup = List.of(
                    "CREATE TABLE t_stat(id INT, v INT)",
                    "CREATE TABLE t_evol(id INT, v INT)",
                    "INSERT INTO t_stat VALUES (1, 10)",
                    "INSERT INTO t_evol VALUES (1, 99)");
            List<String> cleanup = List.of(
                    "DROP TABLE IF EXISTS t_stat",
                    "DROP TABLE IF EXISTS t_evol");

            GaussDBDDLCheckReproducer reproducer = new GaussDBDDLCheckReproducer(
                    setup, "SELECT * FROM t_stat", "SELECT * FROM t_evol", cleanup);

            assertTrue(reproducer.bugStillTriggers(state));
        }
    }

    @Test
    void testReproducerPassesOnEqualData() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:gaussdb_ddlcheck_valid")) {
            GaussDBGlobalState state = new GaussDBGlobalState();
            state.setConnection(new SQLConnection(connection));

            List<String> setup = List.of(
                    "CREATE TABLE t_stat(id INT, v INT)",
                    "CREATE TABLE t_evol(id INT, v INT)",
                    "INSERT INTO t_stat VALUES (1, 10), (2, 20)",
                    "INSERT INTO t_evol VALUES (1, 10), (2, 20)");
            List<String> cleanup = List.of(
                    "DROP TABLE IF EXISTS t_stat",
                    "DROP TABLE IF EXISTS t_evol");

            GaussDBDDLCheckReproducer reproducer = new GaussDBDDLCheckReproducer(
                    setup, "SELECT * FROM t_stat", "SELECT * FROM t_evol", cleanup);

            assertFalse(reproducer.bugStillTriggers(state));
        }
    }
}
