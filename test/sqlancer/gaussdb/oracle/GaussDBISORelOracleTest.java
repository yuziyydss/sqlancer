package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBISORelOracle.ISORelBundle;
import sqlancer.gaussdb.oracle.GaussDBISORelOracle.ISORelMode;

class GaussDBISORelOracleTest {

    @Test
    void testISORelBundleProperties() {
        ISORelBundle bundle = new ISORelBundle(
                ISORelMode.NON_REPEATABLE_READ,
                "t_iso_1",
                "CREATE TABLE t_iso_1 (id INT PRIMARY KEY, val INT);",
                "DROP TABLE t_iso_1;");

        assertNotNull(bundle);
        assertEquals(ISORelMode.NON_REPEATABLE_READ, bundle.getMode());
        assertEquals("t_iso_1", bundle.getTableName());
    }

    @Test
    void testRepeatableReadPassesWhenReadsMatch() {
        List<String> r1 = Collections.singletonList("[100]");
        List<String> r2 = Collections.singletonList("[100]");

        assertDoesNotThrow(() -> GaussDBISORelOracle.verifyNonRepeatableRead(r1, r2, "t0"));
    }

    @Test
    void testRepeatableReadFailsWhenSecondReadChanges() {
        List<String> r1 = Collections.singletonList("[100]");
        List<String> r2 = Collections.singletonList("[999]"); // non-repeatable read!

        assertThrows(AssertionError.class, () -> GaussDBISORelOracle.verifyNonRepeatableRead(r1, r2, "t0"));
    }

    @Test
    void testPhantomReadPassesWhenCountStable() {
        List<String> r1 = Collections.singletonList("[2]");
        List<String> r2 = Collections.singletonList("[2]");

        assertDoesNotThrow(() -> GaussDBISORelOracle.verifyPhantomRead(r1, r2, "t0"));
    }

    @Test
    void testPhantomReadFailsWhenCountIncreases() {
        List<String> r1 = Collections.singletonList("[2]");
        List<String> r2 = Collections.singletonList("[3]"); // phantom read!

        assertThrows(AssertionError.class, () -> GaussDBISORelOracle.verifyPhantomRead(r1, r2, "t0"));
    }

    @Test
    void testWriteSkewDetectedWhenBothCommittedAndConstraintViolated() {
        // tx1 committed, tx2 committed, final balance [0], [0]
        assertThrows(AssertionError.class, () -> GaussDBISORelOracle.verifyWriteSkew(true, true, "[[0], [0]]", "t0"));

        // If one transaction aborted (e.g. 40001 serialization error), no violation
        assertDoesNotThrow(() -> GaussDBISORelOracle.verifyWriteSkew(true, false, "[[0], [100]]", "t0"));
    }

}
