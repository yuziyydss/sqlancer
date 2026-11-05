package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBSQLaserOracle.SQLaserBundle;
import sqlancer.gaussdb.oracle.GaussDBSQLaserOracle.SQLaserMode;

class GaussDBSQLaserOracleTest {

    @Test
    void testSQLaserBundleProperties() {
        SQLaserBundle bundle = new SQLaserBundle(
                SQLaserMode.GROUPING_SETS_UNION_ALL,
                "SELECT c1, c2, COUNT(*) FROM t0 GROUP BY GROUPING SETS ((c1), (c2))",
                "(SELECT c1, NULL, COUNT(*) FROM t0 GROUP BY c1) UNION ALL (SELECT NULL, c2, COUNT(*) FROM t0 GROUP BY c2)");

        assertNotNull(bundle);
        assertEquals(SQLaserMode.GROUPING_SETS_UNION_ALL, bundle.getMode());
        assertEquals("SELECT c1, c2, COUNT(*) FROM t0 GROUP BY GROUPING SETS ((c1), (c2))", bundle.getQuery1());
    }

    @Test
    void testSQLaserGroupingSetsEquivalencePasses() {
        SQLaserBundle bundle = new SQLaserBundle(
                SQLaserMode.GROUPING_SETS_UNION_ALL, "q1", "q2");

        List<String> r1 = Arrays.asList("[1, null, 10]", "[null, 'x', 20]");
        List<String> r2 = Arrays.asList("[null, 'x', 20]", "[1, null, 10]");

        assertDoesNotThrow(() -> GaussDBSQLaserOracle.verifySQLaserInvariants(bundle, r1, r2));
    }

    @Test
    void testSQLaserGroupingSetsFailsOnMultisetDiscrepancy() {
        SQLaserBundle bundle = new SQLaserBundle(
                SQLaserMode.GROUPING_SETS_UNION_ALL, "q1", "q2");

        List<String> r1 = Arrays.asList("[1, null, 10]", "[1, null, 10]"); // 2 rows
        List<String> r2 = Collections.singletonList("[1, null, 10]"); // 1 row

        assertThrows(AssertionError.class, () -> GaussDBSQLaserOracle.verifySQLaserInvariants(bundle, r1, r2));
    }

    @Test
    void testSQLaserCTEWindowMinReductionPasses() {
        SQLaserBundle bundle = new SQLaserBundle(
                SQLaserMode.CTE_WINDOW_MIN_REDUCTION, "q1", "q2");

        List<String> r1 = Arrays.asList("[dept1, 100]", "[dept2, 200]");
        List<String> r2 = Arrays.asList("[dept2, 200]", "[dept1, 100]");

        assertDoesNotThrow(() -> GaussDBSQLaserOracle.verifySQLaserInvariants(bundle, r1, r2));
    }

}
