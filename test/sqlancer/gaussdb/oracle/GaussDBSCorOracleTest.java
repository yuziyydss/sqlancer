package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBSCorOracle.SCorBundle;
import sqlancer.gaussdb.oracle.GaussDBSCorOracle.SCorMode;

class GaussDBSCorOracleTest {

    @Test
    void testSCorBundleProperties() {
        SCorBundle bundle = new SCorBundle(
                SCorMode.AND_SHORT_CIRCUIT,
                "SELECT c0 FROM t0 WHERE (c0 IS NOT NULL AND c0 <> 0) AND (100 / NULLIF(c0, 0) > 5)",
                "SELECT c0 FROM t0 WHERE CASE WHEN (c0 IS NOT NULL AND c0 <> 0) THEN (100 / NULLIF(c0, 0) > 5) ELSE FALSE END");

        assertNotNull(bundle);
        assertEquals(SCorMode.AND_SHORT_CIRCUIT, bundle.getMode());
        assertEquals("SELECT c0 FROM t0 WHERE (c0 IS NOT NULL AND c0 <> 0) AND (100 / NULLIF(c0, 0) > 5)", bundle.getQuery1());
        assertEquals("SELECT c0 FROM t0 WHERE CASE WHEN (c0 IS NOT NULL AND c0 <> 0) THEN (100 / NULLIF(c0, 0) > 5) ELSE FALSE END", bundle.getQuery2());
    }

    @Test
    void testSCorVerificationPasses() {
        SCorBundle bundle = new SCorBundle(
                SCorMode.AND_SHORT_CIRCUIT,
                "q1", "q2");

        List<String> r1 = Arrays.asList("[1]", "[2]", "[3]");
        List<String> r2 = Arrays.asList("[3]", "[2]", "[1]");

        assertDoesNotThrow(() -> GaussDBSCorOracle.verifySCorInvariants(bundle, r1, r2));
    }

    @Test
    void testSCorVerificationFailsOnMismatch() {
        SCorBundle bundle = new SCorBundle(
                SCorMode.AND_SHORT_CIRCUIT,
                "q1", "q2");

        List<String> r1 = Arrays.asList("[1]", "[2]");
        List<String> r2 = Collections.singletonList("[1]"); // missing [2]

        assertThrows(AssertionError.class, () -> GaussDBSCorOracle.verifySCorInvariants(bundle, r1, r2));
    }

    @Test
    void testSCorOrShortCircuitPasses() {
        SCorBundle bundle = new SCorBundle(
                SCorMode.OR_SHORT_CIRCUIT,
                "q1", "q2");

        List<String> r1 = Arrays.asList("[0]", "[null]", "[10]");
        List<String> r2 = Arrays.asList("[0]", "[null]", "[10]");

        assertDoesNotThrow(() -> GaussDBSCorOracle.verifySCorInvariants(bundle, r1, r2));
    }

}
