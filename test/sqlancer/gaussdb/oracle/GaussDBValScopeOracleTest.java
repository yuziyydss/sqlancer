package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.oracle.GaussDBValScopeOracle.ValScopeBundle;
import sqlancer.gaussdb.oracle.GaussDBValScopeOracle.ValScopeMode;

class GaussDBValScopeOracleTest {

    @Test
    void testBundleGeneration() {
        GaussDBColumn col = new GaussDBColumn("c0", GaussDBDataType.INT);
        GaussDBTable table = new GaussDBTable("t0", Collections.singletonList(col),
                Collections.emptyList(), false, true);

        ValScopeBundle aggBundle = GaussDBValScopeOracle.buildAggregatePerturbationBundle(
                table, col, GaussDBConstant.createTrue(), 10);
        assertNotNull(aggBundle);
        assertEquals(ValScopeMode.AGGREGATE_PERTURBATION, aggBundle.getMode());
        assertEquals(10, aggBundle.getShiftK());
        assertTrue(aggBundle.getBaseQuery().contains("SELECT COUNT(*), COALESCE(SUM(c0), 0)"));
        assertTrue(aggBundle.getPerturbedQuery().contains("c0 + (10)"));

        ValScopeBundle winBundle = GaussDBValScopeOracle.buildWindowMonotonicityBundle(
                table, col, GaussDBConstant.createTrue(), 10);
        assertNotNull(winBundle);
        assertEquals(ValScopeMode.WINDOW_MONOTONICITY, winBundle.getMode());
        assertTrue(winBundle.getBaseQuery().contains("DENSE_RANK() OVER (ORDER BY c0 ASC NULLS LAST)"));
        assertTrue(winBundle.getPerturbedQuery().contains("DENSE_RANK() OVER (ORDER BY (c0 + (10)) ASC NULLS LAST)"));

        ValScopeBundle roundtripBundle = GaussDBValScopeOracle.buildScalarRoundtripBundle(
                table, col, GaussDBConstant.createTrue(), 10);
        assertNotNull(roundtripBundle);
        assertEquals(ValScopeMode.SCALAR_ROUNDTRIP, roundtripBundle.getMode());
        assertTrue(roundtripBundle.getPerturbedQuery().contains("((c0 + (10)) - (10))"));
    }

    @Test
    void testAggregatePerturbationVerificationPasses() {
        GaussDBColumn col = new GaussDBColumn("c0", GaussDBDataType.INT);
        GaussDBTable table = new GaussDBTable("t0", Collections.singletonList(col),
                Collections.emptyList(), false, true);
        ValScopeBundle bundle = GaussDBValScopeOracle.buildAggregatePerturbationBundle(
                table, col, GaussDBConstant.createTrue(), 10);

        List<String> baseRows = List.of("[2, 100.0, 50.0, 20.0, 80.0]");
        List<String> pertRows = List.of("[2, 120.0, 60.0, 30.0, 90.0]");

        GaussDBValScopeOracle.verifyAggregatePerturbation(bundle, baseRows, pertRows);
    }

    @Test
    void testAggregatePerturbationVerificationFailsOnDiscrepancy() {
        GaussDBColumn col = new GaussDBColumn("c0", GaussDBDataType.INT);
        GaussDBTable table = new GaussDBTable("t0", Collections.singletonList(col),
                Collections.emptyList(), false, true);
        ValScopeBundle bundle = GaussDBValScopeOracle.buildAggregatePerturbationBundle(
                table, col, GaussDBConstant.createTrue(), 10);

        List<String> baseRows = List.of("[2, 100.0, 50.0, 20.0, 80.0]");
        List<String> invalidPertRows = List.of("[2, 150.0, 60.0, 30.0, 90.0]"); // Expected sum 120, got 150

        assertThrows(AssertionError.class, () ->
                GaussDBValScopeOracle.verifyAggregatePerturbation(bundle, baseRows, invalidPertRows));
    }

    @Test
    void testAreEquivalent() {
        List<String> r1 = Arrays.asList("[1]", "[2]");
        List<String> r2 = Arrays.asList("[2]", "[1]");
        List<String> r3 = Arrays.asList("[1]", "[3]");

        assertTrue(GaussDBValScopeOracle.areEquivalent(r1, r2));
        assertFalse(GaussDBValScopeOracle.areEquivalent(r1, r3));
    }

    @Test
    void testReproducerProperties() {
        ValScopeBundle bundle = new ValScopeBundle(ValScopeMode.SCALAR_ROUNDTRIP, "SELECT c0 FROM t", "SELECT ((c0 + 5) - 5) FROM t", 5);
        GaussDBValScopeReproducer reproducer = new GaussDBValScopeReproducer(bundle);

        assertEquals("SELECT c0 FROM t", reproducer.getBundle().getBaseQuery());
        assertEquals(5, reproducer.getBundle().getShiftK());
        assertEquals(ValScopeMode.SCALAR_ROUNDTRIP, reproducer.getBundle().getMode());
    }
}
