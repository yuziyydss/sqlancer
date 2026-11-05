package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBDDLumosOracle.DDLumosBundle;

class GaussDBDDLumosOracleTest {

    @Test
    void testDDLumosBundleProperties() {
        DDLumosBundle bundle = new DDLumosBundle(
                "t0",
                "SELECT id, c1 FROM t0 ORDER BY id",
                Collections.singletonList("ALTER TABLE t0 ADD COLUMN lumos_c1 INT;"));

        assertNotNull(bundle);
        assertEquals("t0", bundle.getTableName());
        assertEquals("SELECT id, c1 FROM t0 ORDER BY id", bundle.getSnapshotQuery());
        assertEquals(1, bundle.getDdlOperations().size());
    }

    @Test
    void testDDLumosRollbackInvariancePasses() {
        DDLumosBundle bundle = new DDLumosBundle(
                "t0", "SELECT * FROM t0",
                Arrays.asList("ALTER TABLE t0 ADD COLUMN c_tmp INT;", "ROLLBACK;"));

        List<String> preSnapshot = Arrays.asList("[1, 'a']", "[2, 'b']");
        List<String> postSnapshot = Arrays.asList("[1, 'a']", "[2, 'b']");

        assertDoesNotThrow(() -> GaussDBDDLumosOracle.verifyDDLumosInvariants(bundle, preSnapshot, postSnapshot));
    }

    @Test
    void testDDLumosRollbackInvarianceFailsOnStateDivergence() {
        DDLumosBundle bundle = new DDLumosBundle(
                "t0", "SELECT * FROM t0",
                Arrays.asList("ALTER TABLE t0 ADD COLUMN c_tmp INT;", "ROLLBACK;"));

        List<String> preSnapshot = Arrays.asList("[1, 'a']", "[2, 'b']");
        List<String> postSnapshot = Arrays.asList("[1, 'a']", "[2, 'b']", "[3, 'corrupted']");

        assertThrows(AssertionError.class, () -> GaussDBDDLumosOracle.verifyDDLumosInvariants(bundle, preSnapshot, postSnapshot));
    }

}
