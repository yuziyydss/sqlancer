package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBWSSOracle.WSSBundle;

class GaussDBWSSOracleTest {

    @Test
    void testBundleGeneration() {
        WSSBundle bundle = GaussDBWSSOracle.generateBundle();
        assertNotNull(bundle);
        assertTrue(bundle.getTableName().startsWith("t_wss_"));
        assertTrue(bundle.getSetupSql().contains("CREATE TABLE"));
        assertTrue(bundle.getSetupSql().contains("PRIMARY KEY"));
        assertEquals(2, bundle.getTx1Sql().size());
        assertEquals(2, bundle.getTx2Sql().size());
        assertTrue(bundle.getValidStates().contains("300:11"));
        assertTrue(bundle.getValidStates().contains("250:11"));
        assertNotNull(bundle.getCleanupSql());
    }

    @Test
    void testReproducerProperties() {
        GaussDBWSSReproducer reproducer = new GaussDBWSSReproducer(
                "CREATE TABLE t(id INT)",
                "SERIALIZABLE",
                List.of("UPDATE t SET balance = balance + 50"),
                List.of("UPDATE t SET balance = balance * 2"),
                "SELECT balance, counter FROM t",
                Set.of("300:11", "250:11"),
                "DROP TABLE t");

        assertNotNull(reproducer.getSetupSql());
        assertEquals("SERIALIZABLE", reproducer.getIsolationLevel());
        assertEquals(1, reproducer.getTx1Sql().size());
        assertEquals(1, reproducer.getTx2Sql().size());
        assertTrue(reproducer.getValidStates().contains("300:11"));
    }
}
