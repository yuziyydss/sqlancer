package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBSedarOracle.SedarBundle;

class GaussDBSedarOracleTest {

    @Test
    void testSedarBundleProperties() {
        SedarBundle bundle = new SedarBundle(
                "t_sedar_1",
                "CREATE TABLE t_sedar_1 (id INT PRIMARY KEY, balance INT);",
                50L,
                "DROP TABLE t_sedar_1;");

        assertNotNull(bundle);
        assertEquals("t_sedar_1", bundle.getTableName());
        assertEquals(50L, bundle.getDelayMs());
    }

    @Test
    void testSedarInvariancePassesWhenAccountsBalance() {
        // Expected balances 80 and 120
        assertDoesNotThrow(() ->
                GaussDBSedarOracle.verifySedarInvariants(true, true, "[[80, 80], [120, 120]]", "t0"));
    }

    @Test
    void testSedarInvarianceFailsOnLostUpdate() {
        // One update lost, balance is 70 and 100
        assertThrows(AssertionError.class, () ->
                GaussDBSedarOracle.verifySedarInvariants(true, true, "[[70, 70], [100, 100]]", "t0"));
    }

    @Test
    void testSedarPassesWhenOneTransactionAborts() {
        // If tx1 or tx2 aborted due to serialization/deadlock, no assertion error
        assertDoesNotThrow(() ->
                GaussDBSedarOracle.verifySedarInvariants(true, false, "[[70, 70], [130, 130]]", "t0"));
    }

}
