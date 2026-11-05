package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBTxCheckOracle.TxCheckBundle;
import sqlancer.gaussdb.oracle.GaussDBTxCheckOracle.TxCheckMode;

class GaussDBTxCheckOracleTest {

    @Test
    void testTxCheckBundleProperties() {
        TxCheckBundle bundle = new TxCheckBundle(
                TxCheckMode.ANTI_DEPENDENCY_CYCLE,
                "t_tx_1",
                "CREATE TABLE t_tx_1 (id INT PRIMARY KEY, val INT);",
                "DROP TABLE t_tx_1;");

        assertNotNull(bundle);
        assertEquals(TxCheckMode.ANTI_DEPENDENCY_CYCLE, bundle.getMode());
        assertEquals("t_tx_1", bundle.getTableName());
    }

    @Test
    void testAntiDependencyCycleFailsWhenBothCommittedAndStateViolated() {
        assertThrows(AssertionError.class, () ->
                GaussDBTxCheckOracle.verifyAntiDependencyCycle(true, true, "[[1], [1]]", "t0"));

        // If one transaction was aborted by SSI, no violation
        assertDoesNotThrow(() ->
                GaussDBTxCheckOracle.verifyAntiDependencyCycle(true, false, "[[0], [1]]", "t0"));
    }

    @Test
    void testCausalReadSkewPassesWhenConsistent() {
        List<String> rY = Collections.singletonList("[1]");
        List<String> rX = Collections.singletonList("[1]");

        assertDoesNotThrow(() -> GaussDBTxCheckOracle.verifyCausalReadSkew(rY, rX, "t0"));
    }

    @Test
    void testCausalReadSkewFailsWhenStaleReadObserved() {
        List<String> rY = Collections.singletonList("[1]");
        List<String> rX = Collections.singletonList("[0]"); // saw y=1 but stale x=0!

        assertThrows(AssertionError.class, () -> GaussDBTxCheckOracle.verifyCausalReadSkew(rY, rX, "t0"));
    }

}
