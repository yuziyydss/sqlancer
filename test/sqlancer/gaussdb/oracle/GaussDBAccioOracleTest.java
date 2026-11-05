package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBAccioOracle.AccioBundle;
import sqlancer.gaussdb.oracle.GaussDBAccioOracle.AccioMode;

class GaussDBAccioOracleTest {

    @Test
    void testAccioBundleProperties() {
        AccioBundle bundle = new AccioBundle(
                AccioMode.RBAC_TABLE_ACCESS_CONTROL,
                "role_test",
                Collections.singletonList("CREATE ROLE role_test;"),
                "SELECT * FROM t0;",
                "DROP ROLE role_test;",
                "permission denied",
                null);

        assertNotNull(bundle);
        assertEquals(AccioMode.RBAC_TABLE_ACCESS_CONTROL, bundle.getMode());
        assertEquals("role_test", bundle.getRoleName());
    }

    @Test
    void testNegativeAccessBlockedPassesWhenDenied() {
        // Access was properly blocked (accessSucceeded = false)
        assertDoesNotThrow(() -> GaussDBAccioOracle.verifyNegativeAccessBlocked(false, "guest", "SELECT * FROM t_secret"));
    }

    @Test
    void testNegativeAccessBlockedFailsOnPrivilegeEscalation() {
        // Access succeeded when it should have been blocked -> CVE!
        assertThrows(AssertionError.class,
                () -> GaussDBAccioOracle.verifyNegativeAccessBlocked(true, "guest", "SELECT * FROM t_secret"));
    }

    @Test
    void testRLSLeakagePassesWhenAllRowsMatchPolicy() {
        List<String> returned = Arrays.asList("PUBLIC", "PUBLIC", "PUBLIC");
        assertDoesNotThrow(() -> GaussDBAccioOracle.verifyRLSLeakage(returned, "PUBLIC"));
    }

    @Test
    void testRLSLeakageFailsWhenSecretRowLeaked() {
        List<String> returnedWithLeak = Arrays.asList("PUBLIC", "CONFIDENTIAL", "PUBLIC");
        assertThrows(AssertionError.class, () -> GaussDBAccioOracle.verifyRLSLeakage(returnedWithLeak, "PUBLIC"));
    }
}
