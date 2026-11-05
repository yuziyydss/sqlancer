package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBPinoloOracle.PinoloBundle;
import sqlancer.gaussdb.oracle.GaussDBPinoloOracle.PinoloMode;

class GaussDBPinoloOracleTest {

    @Test
    void testPinoloBundleProperties() {
        PinoloBundle bundle = new PinoloBundle(
                PinoloMode.SET_SUBSUMPTION,
                "SELECT id FROM t0 WHERE c1 > 0",
                "SELECT id FROM t0 WHERE (c1 > 0) AND (c2 IS NOT NULL)",
                "SELECT id FROM t0 WHERE (c1 > 0) OR (c2 IS NOT NULL)");

        assertNotNull(bundle);
        assertEquals(PinoloMode.SET_SUBSUMPTION, bundle.getMode());
        assertEquals("SELECT id FROM t0 WHERE c1 > 0", bundle.getBaseQuery());
        assertEquals("SELECT id FROM t0 WHERE (c1 > 0) AND (c2 IS NOT NULL)", bundle.getSubQuery());
        assertEquals("SELECT id FROM t0 WHERE (c1 > 0) OR (c2 IS NOT NULL)", bundle.getSuperQuery());
    }

    @Test
    void testSetSubsumptionValidInvariants() {
        PinoloBundle bundle = new PinoloBundle(
                PinoloMode.SET_SUBSUMPTION,
                "SELECT id FROM t0 WHERE c1 > 0",
                "SELECT id FROM t0 WHERE (c1 > 0) AND (c2 IS NOT NULL)",
                "SELECT id FROM t0 WHERE (c1 > 0) OR (c2 IS NOT NULL)");

        List<String> subRows = Arrays.asList("[1]", "[2]");
        List<String> baseRows = Arrays.asList("[1]", "[2]", "[3]");
        List<String> superRows = Arrays.asList("[1]", "[2]", "[3]", "[4]");

        assertDoesNotThrow(() -> GaussDBPinoloOracle.verifyPinoloInvariants(bundle, subRows, baseRows, superRows));
    }

    @Test
    void testSetSubsumptionViolationSubNotInBase() {
        PinoloBundle bundle = new PinoloBundle(
                PinoloMode.SET_SUBSUMPTION,
                "SELECT id FROM t0 WHERE c1 > 0",
                "SELECT id FROM t0 WHERE (c1 > 0) AND (c2 IS NOT NULL)",
                "SELECT id FROM t0 WHERE (c1 > 0) OR (c2 IS NOT NULL)");

        List<String> subRows = Arrays.asList("[1]", "[99]"); // 99 not in base!
        List<String> baseRows = Arrays.asList("[1]", "[2]", "[3]");
        List<String> superRows = Arrays.asList("[1]", "[2]", "[3]", "[4]");

        assertThrows(AssertionError.class, () -> GaussDBPinoloOracle.verifyPinoloInvariants(bundle, subRows, baseRows, superRows));
    }

    @Test
    void testSetSubsumptionViolationBaseNotInSuper() {
        PinoloBundle bundle = new PinoloBundle(
                PinoloMode.SET_SUBSUMPTION,
                "SELECT id FROM t0 WHERE c1 > 0",
                "SELECT id FROM t0 WHERE (c1 > 0) AND (c2 IS NOT NULL)",
                "SELECT id FROM t0 WHERE (c1 > 0) OR (c2 IS NOT NULL)");

        List<String> subRows = Collections.singletonList("[1]");
        List<String> baseRows = Arrays.asList("[1]", "[99]"); // 99 not in super!
        List<String> superRows = Arrays.asList("[1]", "[2]", "[3]");

        assertThrows(AssertionError.class, () -> GaussDBPinoloOracle.verifyPinoloInvariants(bundle, subRows, baseRows, superRows));
    }

    @Test
    void testCountBoundValidAndViolated() {
        PinoloBundle bundle = new PinoloBundle(
                PinoloMode.COUNT_BOUND,
                "SELECT COUNT(*) FROM t0 WHERE c1 > 0",
                "SELECT COUNT(*) FROM t0 WHERE (c1 > 0) AND (c2 IS NOT NULL)",
                "SELECT COUNT(*) FROM t0 WHERE (c1 > 0) OR (c2 IS NOT NULL)");

        List<String> subRows = Collections.singletonList("[5]");
        List<String> baseRows = Collections.singletonList("[10]");
        List<String> superRows = Collections.singletonList("[15]");

        assertDoesNotThrow(() -> GaussDBPinoloOracle.verifyPinoloInvariants(bundle, subRows, baseRows, superRows));

        List<String> invalidSub = Collections.singletonList("[12]"); // 12 > 10 violated!
        assertThrows(AssertionError.class, () -> GaussDBPinoloOracle.verifyPinoloInvariants(bundle, invalidSub, baseRows, superRows));
    }

}
