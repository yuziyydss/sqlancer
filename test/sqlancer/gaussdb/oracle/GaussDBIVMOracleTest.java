package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBIVMOracle.IVMBundle;
import sqlancer.gaussdb.oracle.GaussDBIVMOracle.IVMMode;

class GaussDBIVMOracleTest {

    @Test
    void testIVMBundleProperties() {
        IVMBundle bundle = new IVMBundle(
                IVMMode.FULL_REFRESH_CONSISTENCY,
                "mv_test_1",
                "CREATE MATERIALIZED VIEW mv_test_1 AS SELECT * FROM t0;",
                "SELECT * FROM t0",
                Collections.singletonList("UPDATE t0 SET id = id + 1;"),
                "REFRESH MATERIALIZED VIEW mv_test_1;",
                "SELECT * FROM mv_test_1;",
                "SELECT * FROM (SELECT * FROM t0) AS v;",
                "DROP MATERIALIZED VIEW mv_test_1;");

        assertNotNull(bundle);
        assertEquals(IVMMode.FULL_REFRESH_CONSISTENCY, bundle.getMode());
        assertEquals("mv_test_1", bundle.getMvName());
    }

    @Test
    void testIVMInvariancePassesWhenMultisetsMatch() {
        IVMBundle bundle = new IVMBundle(
                IVMMode.FULL_REFRESH_CONSISTENCY, "mv_1", "create", "queryDef",
                Collections.emptyList(), "refresh", "qMv", "qBase", "clean");

        List<String> mvRows = Arrays.asList("[1, 100]", "[2, 200]");
        List<String> baseRows = Arrays.asList("[2, 200]", "[1, 100]");

        assertDoesNotThrow(() -> GaussDBIVMOracle.verifyIVMInvariants(bundle, mvRows, baseRows));
    }

    @Test
    void testIVMInvarianceFailsWhenViewStaleOrDrifted() {
        IVMBundle bundle = new IVMBundle(
                IVMMode.FULL_REFRESH_CONSISTENCY, "mv_1", "create", "queryDef",
                Collections.emptyList(), "refresh", "qMv", "qBase", "clean");

        List<String> mvRows = Collections.singletonList("[1, 100]"); // Stale row
        List<String> baseRows = Arrays.asList("[1, 100]", "[3, 300]"); // Base has newly inserted row 3

        assertThrows(AssertionError.class, () -> GaussDBIVMOracle.verifyIVMInvariants(bundle, mvRows, baseRows));
    }

}
