package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBFucciOracle.FucciBundle;
import sqlancer.gaussdb.oracle.GaussDBFucciOracle.FucciMode;

class GaussDBFucciOracleTest {

    @Test
    void testFucciBundleProperties() {
        FucciBundle bundle = new FucciBundle(
                FucciMode.LOCK_COMMIT_INVARIANT,
                "t_fucci_1",
                "CREATE TABLE t_fucci_1 (id INT, val INT, version INT);",
                "DROP TABLE t_fucci_1;");

        assertNotNull(bundle);
        assertEquals(FucciMode.LOCK_COMMIT_INVARIANT, bundle.getMode());
        assertEquals("t_fucci_1", bundle.getTableName());
    }

    @Test
    void testFucciCommitInvariantPasses() {
        FucciBundle bundle = new FucciBundle(
                FucciMode.LOCK_COMMIT_INVARIANT, "t0", "setup", "clean");

        // T1 committed, T2 updated on top: val=200, version=3
        assertDoesNotThrow(() -> GaussDBFucciOracle.verifyFucciInvariants(bundle, true, "[200, 3]", "t0"));
    }

    @Test
    void testFucciCommitInvariantFailsOnLostUpdate() {
        FucciBundle bundle = new FucciBundle(
                FucciMode.LOCK_COMMIT_INVARIANT, "t0", "setup", "clean");

        // Expected version=3, but got version=2 (T1's update lost!)
        assertThrows(AssertionError.class, () ->
                GaussDBFucciOracle.verifyFucciInvariants(bundle, true, "[200, 2]", "t0"));
    }

    @Test
    void testFucciRollbackInvariantPasses() {
        FucciBundle bundle = new FucciBundle(
                FucciMode.LOCK_ROLLBACK_INVARIANT, "t0", "setup", "clean");

        // T1 rolled back, T2 updated on base: val=200, version=2
        assertDoesNotThrow(() -> GaussDBFucciOracle.verifyFucciInvariants(bundle, true, "[200, 2]", "t0"));
    }

    @Test
    void testFucciRollbackInvariantFailsOnDirtyLeakage() {
        FucciBundle bundle = new FucciBundle(
                FucciMode.LOCK_ROLLBACK_INVARIANT, "t0", "setup", "clean");

        // Expected version=2, but got version=3 (T1 rolled back but dirty version survived!)
        assertThrows(AssertionError.class, () ->
                GaussDBFucciOracle.verifyFucciInvariants(bundle, true, "[200, 3]", "t0"));
    }

}
