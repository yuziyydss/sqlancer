package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import sqlancer.gaussdb.oracle.GaussDBEDCOracle.EDCBundle;

class GaussDBEDCOracleTest {

    @Test
    void testBundleGenerationNoNumericColumns() {
        GaussDBColumn textCol = new GaussDBColumn("c_text", GaussDBDataType.TEXT);
        GaussDBTable table = new GaussDBTable("t_text", Collections.singletonList(textCol), Collections.emptyList(), false, true);

        EDCBundle bundle = GaussDBEDCOracle.generateBundle(table);
        assertNull(bundle);
    }

    @Test
    void testBundleGenerationWithNumericColumns() {
        GaussDBColumn intCol = new GaussDBColumn("c_int", GaussDBDataType.INT);
        GaussDBColumn textCol = new GaussDBColumn("c_text", GaussDBDataType.TEXT);
        GaussDBTable table = new GaussDBTable("t_mixed", List.of(intCol, textCol), Collections.emptyList(), false, true);

        EDCBundle bundle = GaussDBEDCOracle.generateBundle(table);
        assertNotNull(bundle);
        assertFalse(bundle.getSetupSql().isEmpty());
        assertTrue(bundle.getSetupSql().get(1).contains("CREATE TEMPORARY TABLE t_edc_"));
        assertNotNull(bundle.getBaseQuery());
        assertNotNull(bundle.getShiftedQuery());
        assertFalse(bundle.getCleanupSql().isEmpty());
    }

    @Test
    void testReproducerDetectsDiscrepancy() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:gaussdb_edc_diff")) {
            GaussDBGlobalState state = new GaussDBGlobalState();
            state.setConnection(new SQLConnection(connection));

            List<String> setup = List.of(
                    "CREATE TABLE t0(id INT, v INT)",
                    "CREATE TABLE t0_shift(id INT, v_shift INT)",
                    "INSERT INTO t0 VALUES (1, 10)",
                    "INSERT INTO t0_shift VALUES (1, 999)");
            List<String> cleanup = List.of(
                    "DROP TABLE IF EXISTS t0",
                    "DROP TABLE IF EXISTS t0_shift");

            GaussDBEDCReproducer reproducer = new GaussDBEDCReproducer(
                    setup, "SELECT * FROM t0", "SELECT * FROM t0_shift", cleanup);

            assertTrue(reproducer.bugStillTriggers(state));
        }
    }

    @Test
    void testReproducerPassesOnEqualData() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:gaussdb_edc_same")) {
            GaussDBGlobalState state = new GaussDBGlobalState();
            state.setConnection(new SQLConnection(connection));

            List<String> setup = List.of(
                    "CREATE TABLE t0(id INT, v INT)",
                    "CREATE TABLE t0_shift(id INT, v_shift INT)",
                    "INSERT INTO t0 VALUES (1, 10)",
                    "INSERT INTO t0_shift VALUES (1, 10)");
            List<String> cleanup = List.of(
                    "DROP TABLE IF EXISTS t0",
                    "DROP TABLE IF EXISTS t0_shift");

            GaussDBEDCReproducer reproducer = new GaussDBEDCReproducer(
                    setup, "SELECT * FROM t0", "SELECT * FROM t0_shift", cleanup);

            assertFalse(reproducer.bugStillTriggers(state));
        }
    }
}
