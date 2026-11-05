package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBPuppyOracle.PuppyBundle;
import sqlancer.gaussdb.oracle.GaussDBPuppyOracle.PuppyConstraintScenario;

class GaussDBPuppyOracleTest {

    @Test
    void testPuppyBundleProperties() {
        PuppyBundle bundle = new PuppyBundle(
                PuppyConstraintScenario.DISABLE_HASH_JOIN,
                "SELECT * FROM t0",
                PuppyConstraintScenario.DISABLE_HASH_JOIN.getGucCommands());

        assertNotNull(bundle);
        assertEquals(PuppyConstraintScenario.DISABLE_HASH_JOIN, bundle.getScenario());
        assertEquals("SELECT * FROM t0", bundle.getQuery());
        assertEquals(Collections.singletonList("SET enable_hashjoin = off;"), bundle.getGucCommands());
    }

    @Test
    void testPuppyInvariancePassesWhenResultsMatch() {
        PuppyBundle bundle = new PuppyBundle(
                PuppyConstraintScenario.FORCE_NESTLOOP,
                "SELECT id FROM t0 JOIN t1 ON t0.id = t1.id",
                PuppyConstraintScenario.FORCE_NESTLOOP.getGucCommands());

        List<String> defaultRows = Arrays.asList("[1]", "[2]", "[3]");
        List<String> constrainedRows = Arrays.asList("[2]", "[1]", "[3]");

        assertDoesNotThrow(() -> GaussDBPuppyOracle.verifyPuppyInvariants(bundle, defaultRows, constrainedRows));
    }

    @Test
    void testPuppyInvarianceFailsWhenConstrainedPlanDropsRow() {
        PuppyBundle bundle = new PuppyBundle(
                PuppyConstraintScenario.FORCE_INDEXSCAN,
                "SELECT id FROM t0 WHERE c1 > 0",
                PuppyConstraintScenario.FORCE_INDEXSCAN.getGucCommands());

        List<String> defaultRows = Arrays.asList("[1]", "[2]", "[3]");
        List<String> constrainedRows = Arrays.asList("[1]", "[2]"); // missing [3]

        assertThrows(AssertionError.class, () -> GaussDBPuppyOracle.verifyPuppyInvariants(bundle, defaultRows, constrainedRows));
    }

    @Test
    void testPuppyAllScenariosHaveGucCommands() {
        for (PuppyConstraintScenario scenario : PuppyConstraintScenario.values()) {
            assertNotNull(scenario.getGucCommands());
            org.junit.jupiter.api.Assertions.assertFalse(scenario.getGucCommands().isEmpty());
        }
    }

}
