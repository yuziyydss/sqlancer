package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBJITOracle.JITVariant;

class GaussDBJITOracleTest {

    @Test
    void testJITVariants() {
        List<JITVariant> variants = GaussDBJITOracle.getJITVariants();
        assertNotNull(variants);
        assertEquals(3, variants.size());

        JITVariant fullJit = variants.get(0);
        assertEquals("FULL_JIT", fullJit.getName());
        assertTrue(fullJit.getGucSettings().contains("SET jit = on"));
        assertTrue(fullJit.getGucSettings().contains("SET jit_above_cost = 0"));
        assertTrue(fullJit.getGucSettings().contains("SET jit_expressions = on"));
        assertTrue(fullJit.getGucSettings().contains("SET jit_tuple_deforming = on"));

        JITVariant exprJit = variants.get(1);
        assertEquals("EXPRESSIONS_ONLY_JIT", exprJit.getName());
        assertTrue(exprJit.getGucSettings().contains("SET jit_expressions = on"));
        assertTrue(exprJit.getGucSettings().contains("SET jit_tuple_deforming = off"));

        JITVariant deformJit = variants.get(2);
        assertEquals("DEFORMING_ONLY_JIT", deformJit.getName());
        assertTrue(deformJit.getGucSettings().contains("SET jit_expressions = off"));
        assertTrue(deformJit.getGucSettings().contains("SET jit_tuple_deforming = on"));
    }

    @Test
    void testResetGUCs() {
        List<String> resetGucs = GaussDBJITOracle.RESET_JIT_GUCS;
        assertNotNull(resetGucs);
        assertTrue(resetGucs.contains("RESET jit"));
        assertTrue(resetGucs.contains("RESET jit_above_cost"));
        assertTrue(resetGucs.contains("RESET jit_expressions"));
        assertTrue(resetGucs.contains("RESET jit_tuple_deforming"));
    }

    @Test
    void testAreEquivalent() {
        List<String> r1 = Arrays.asList("[1, foo]", "[2, bar]");
        List<String> r2 = Arrays.asList("[2, bar]", "[1, foo]");
        List<String> r3 = Arrays.asList("[1, foo]", "[3, baz]");
        List<String> r4 = Arrays.asList("[1, foo]");

        assertTrue(GaussDBJITOracle.areEquivalent(r1, r2));
        assertFalse(GaussDBJITOracle.areEquivalent(r1, r3));
        assertFalse(GaussDBJITOracle.areEquivalent(r1, r4));
    }

    @Test
    void testReproducerProperties() {
        JITVariant variant = new JITVariant("TEST_JIT", List.of("SET jit = on"));
        GaussDBJITReproducer reproducer = new GaussDBJITReproducer("SELECT 1", variant);

        assertEquals("SELECT 1", reproducer.getQuery());
        assertEquals("TEST_JIT", reproducer.getVariant().getName());
        assertEquals(List.of("SET jit = on"), reproducer.getVariant().getGucSettings());
    }
}
