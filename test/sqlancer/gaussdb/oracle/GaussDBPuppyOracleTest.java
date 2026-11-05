package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBPuppyOracle.PuppyBundle;
import sqlancer.gaussdb.oracle.GaussDBPuppyOracle.PuppyConstraintScenario;
import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBToStringVisitor;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

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

    @Test
    void testPuppyResetCommandsAreTargeted() {
        List<String> expected = Arrays.asList(
                "RESET enable_hashjoin;",
                "RESET enable_mergejoin;",
                "RESET enable_seqscan;",
                "RESET enable_indexscan;",
                "RESET enable_bitmapscan;",
                "RESET enable_material;",
                "RESET enable_sort;");

        assertEquals(expected, GaussDBPuppyOracle.PUPPY_RESET_GUC_COMMANDS);
        assertFalse(GaussDBPuppyOracle.PUPPY_RESET_GUC_COMMANDS.contains("RESET ALL;"));
    }

    @Test
    void testJoinPredicateUsesAlias() {
        GaussDBGlobalState state = new GaussDBGlobalState();
        state.setMainOptions(new MainOptions());
        state.setRandomly(new Randomly(0));

        GaussDBColumn column = new GaussDBColumn("c0", GaussDBDataType.INT);
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(List.of(column));
        generator.setTableAlias("a");

        boolean sawAlias = false;
        for (int i = 0; i < 100; i++) {
            String predicate = GaussDBToStringVisitor.asString(generator.generatePredicate());
            if (predicate.contains("a.c0")) {
                sawAlias = true;
                break;
            }
        }
        assertTrue(sawAlias);
    }

}
