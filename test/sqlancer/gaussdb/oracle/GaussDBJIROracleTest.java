package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBJIROracle.JIRBundle;
import sqlancer.gaussdb.oracle.GaussDBJIROracle.JIRMode;

class GaussDBJIROracleTest {

    @Test
    void testJIRBundleProperties() {
        JIRBundle bundle = new JIRBundle(
                JIRMode.NULL_REJECTING_OUTER_TO_INNER,
                "SELECT DISTINCT a.id, b.id FROM t1 a LEFT JOIN t2 b ON a.id = b.id WHERE b.id IS NOT NULL",
                "SELECT DISTINCT a.id, b.id FROM t1 a INNER JOIN t2 b ON a.id = b.id WHERE b.id IS NOT NULL");

        assertNotNull(bundle);
        assertEquals(JIRMode.NULL_REJECTING_OUTER_TO_INNER, bundle.getMode());
        assertEquals("SELECT DISTINCT a.id, b.id FROM t1 a LEFT JOIN t2 b ON a.id = b.id WHERE b.id IS NOT NULL", bundle.getQuery1());
        assertEquals("SELECT DISTINCT a.id, b.id FROM t1 a INNER JOIN t2 b ON a.id = b.id WHERE b.id IS NOT NULL", bundle.getQuery2());
    }

    @Test
    void testNullRejectingEquivalencePasses() {
        JIRBundle bundle = new JIRBundle(
                JIRMode.NULL_REJECTING_OUTER_TO_INNER,
                "q1", "q2");

        List<String> r1 = Arrays.asList("[1, 10]", "[2, 20]");
        List<String> r2 = Arrays.asList("[2, 20]", "[1, 10]");

        assertDoesNotThrow(() -> GaussDBJIROracle.verifyJIRInvariants(bundle, r1, r2));
    }

    @Test
    void testNullRejectingEquivalenceFailsOnDiscrepancy() {
        JIRBundle bundle = new JIRBundle(
                JIRMode.NULL_REJECTING_OUTER_TO_INNER,
                "q1", "q2");

        List<String> r1 = Arrays.asList("[1, 10]", "[2, 20]");
        List<String> r2 = Collections.singletonList("[1, 10]"); // missing row

        assertThrows(AssertionError.class, () -> GaussDBJIROracle.verifyJIRInvariants(bundle, r1, r2));
    }

    @Test
    void testInnerInclusionProjectionPasses() {
        JIRBundle bundle = new JIRBundle(
                JIRMode.INNER_INCLUSION_PROJECTION,
                "qInner", "qLeft");

        List<String> rInner = Arrays.asList("[1]", "[2]");
        List<String> rLeft = Arrays.asList("[1]", "[2]", "[3]");

        assertDoesNotThrow(() -> GaussDBJIROracle.verifyJIRInvariants(bundle, rInner, rLeft));
    }

    @Test
    void testInnerInclusionProjectionFailsWhenInnerHasExtraRow() {
        JIRBundle bundle = new JIRBundle(
                JIRMode.INNER_INCLUSION_PROJECTION,
                "qInner", "qLeft");

        List<String> rInner = Arrays.asList("[1]", "[99]"); // 99 not in left!
        List<String> rLeft = Arrays.asList("[1]", "[2]", "[3]");

        assertThrows(AssertionError.class, () -> GaussDBJIROracle.verifyJIRInvariants(bundle, rInner, rLeft));
    }

    @Test
    void testTransitiveEquijoinImplication() {
        JIRBundle bundle = new JIRBundle(
                JIRMode.TRANSITIVE_EQUIJOIN_IMPLICATION,
                "qExplicit", "qImplied");

        List<String> r1 = Arrays.asList("[1, 10, 100]", "[2, 20, 200]");
        List<String> r2 = Arrays.asList("[2, 20, 200]", "[1, 10, 100]");

        assertDoesNotThrow(() -> GaussDBJIROracle.verifyJIRInvariants(bundle, r1, r2));
    }

}
