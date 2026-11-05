package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBDistSQLOracle.DistSQLBundle;
import sqlancer.gaussdb.oracle.GaussDBDistSQLOracle.DistSQLMode;

class GaussDBDistSQLOracleTest {

    @Test
    void testDistSQLBundleProperties() {
        DistSQLBundle bundle = new DistSQLBundle(
                DistSQLMode.DISTRIBUTED_AGGREGATION,
                "SELECT c0, COUNT(*) FROM t0 GROUP BY c0",
                Collections.singletonList("SET query_dop = 1;"),
                Collections.singletonList("SET query_dop = 4;"));

        assertNotNull(bundle);
        assertEquals(DistSQLMode.DISTRIBUTED_AGGREGATION, bundle.getMode());
        assertEquals("SELECT c0, COUNT(*) FROM t0 GROUP BY c0", bundle.getQuery());
    }

    @Test
    void testDistSQLInvariancePassesWhenMultisetsMatch() {
        DistSQLBundle bundle = new DistSQLBundle(
                DistSQLMode.DISTRIBUTED_AGGREGATION, "q",
                Collections.emptyList(), Collections.emptyList());

        List<String> rCentral = Arrays.asList("[dept1, 10]", "[dept2, 20]");
        List<String> rDist = Arrays.asList("[dept2, 20]", "[dept1, 10]"); // Order may differ due to stream workers

        assertDoesNotThrow(() -> GaussDBDistSQLOracle.verifyDistSQLInvariants(bundle, rCentral, rDist));
    }

    @Test
    void testDistSQLInvarianceFailsOnTupleDropOrDivergence() {
        DistSQLBundle bundle = new DistSQLBundle(
                DistSQLMode.DISTRIBUTED_JOIN_STREAMING, "q",
                Collections.emptyList(), Collections.emptyList());

        List<String> rCentral = Arrays.asList("[1, 100]", "[2, 200]");
        List<String> rDist = Collections.singletonList("[1, 100]"); // Stream drop on DN!

        assertThrows(AssertionError.class, () -> GaussDBDistSQLOracle.verifyDistSQLInvariants(bundle, rCentral, rDist));
    }

    @Test
    void testDistSQLWindowStreamingPasses() {
        DistSQLBundle bundle = new DistSQLBundle(
                DistSQLMode.DISTRIBUTED_WINDOW_STREAMING, "q",
                Collections.emptyList(), Collections.emptyList());

        List<String> r1 = Arrays.asList("[1, 10, 1]", "[2, 20, 1]");
        List<String> r2 = Arrays.asList("[1, 10, 1]", "[2, 20, 1]");

        assertDoesNotThrow(() -> GaussDBDistSQLOracle.verifyDistSQLInvariants(bundle, r1, r2));
    }

}
