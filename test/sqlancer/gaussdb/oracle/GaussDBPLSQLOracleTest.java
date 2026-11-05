package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.oracle.GaussDBPLSQLOracle.PLSQLBundle;
import sqlancer.gaussdb.oracle.GaussDBPLSQLOracle.PLSQLMode;

class GaussDBPLSQLOracleTest {

    @Test
    void testCursorLoopBundle() {
        GaussDBColumn col = new GaussDBColumn("c0", GaussDBDataType.INT);
        GaussDBTable table = new GaussDBTable("t0", Collections.singletonList(col),
                Collections.emptyList(), false, true);

        PLSQLBundle bundle = GaussDBPLSQLOracle.buildCursorLoopBundle(
                "_fn_test_1", table, col, GaussDBConstant.createTrue());

        assertNotNull(bundle);
        assertEquals(PLSQLMode.CURSOR_LOOP, bundle.getMode());
        assertEquals("_fn_test_1", bundle.getFunctionName());
        assertTrue(bundle.getCreateFunctionSql().contains("CURSOR cur IS SELECT c0 FROM t0 t WHERE TRUE;"));
        assertTrue(bundle.getCreateFunctionSql().contains("FOR r IN cur LOOP"));
        assertTrue(bundle.getCallFunctionSql().contains("SELECT cnt, total FROM _fn_test_1()"));
        assertTrue(bundle.getDeclarativeSql().contains("SELECT COUNT(*), COALESCE(SUM(c0), 0) FROM t0 t WHERE TRUE"));
        assertTrue(bundle.getDropFunctionSql().contains("DROP FUNCTION IF EXISTS _fn_test_1()"));
    }

    @Test
    void testDynamicSQLBundle() {
        GaussDBColumn col = new GaussDBColumn("c0", GaussDBDataType.INT);
        GaussDBTable table = new GaussDBTable("t0", Collections.singletonList(col),
                Collections.emptyList(), false, true);

        PLSQLBundle bundle = GaussDBPLSQLOracle.buildDynamicSQLBundle(
                "_fn_test_2", table, col, GaussDBConstant.createTrue());

        assertNotNull(bundle);
        assertEquals(PLSQLMode.DYNAMIC_SQL, bundle.getMode());
        assertTrue(bundle.getCreateFunctionSql().contains("EXECUTE IMMEDIATE"));
        assertTrue(bundle.getCreateFunctionSql().contains("INTO v_cnt, v_sum;"));
    }

    @Test
    void testConditionalBranchBundle() {
        GaussDBColumn col = new GaussDBColumn("c0", GaussDBDataType.INT);
        GaussDBTable table = new GaussDBTable("t0", Collections.singletonList(col),
                Collections.emptyList(), false, true);

        PLSQLBundle bundle = GaussDBPLSQLOracle.buildConditionalBranchBundle(
                "_fn_test_3", table, col, GaussDBConstant.createTrue());

        assertNotNull(bundle);
        assertEquals(PLSQLMode.CONDITIONAL_BRANCH, bundle.getMode());
        assertTrue(bundle.getCreateFunctionSql().contains("IF v_cnt > 0 THEN"));
        assertTrue(bundle.getCreateFunctionSql().contains("ELSE"));
        assertTrue(bundle.getDeclarativeSql().contains("CASE WHEN COUNT(*) > 0 THEN COALESCE(SUM(c0), 0) + 10 ELSE -1 END"));
    }

    @Test
    void testAreEquivalent() {
        List<String> r1 = Arrays.asList("[5, 100]");
        List<String> r2 = Arrays.asList("[5, 100]");
        List<String> r3 = Arrays.asList("[5, 99]");

        assertTrue(GaussDBPLSQLOracle.areEquivalent(r1, r2));
        assertFalse(GaussDBPLSQLOracle.areEquivalent(r1, r3));
    }

    @Test
    void testReproducerProperties() {
        PLSQLBundle bundle = new PLSQLBundle(PLSQLMode.CURSOR_LOOP, "_fn", "CREATE ...", "SELECT ...", "DECL ...", "DROP ...");
        GaussDBPLSQLReproducer reproducer = new GaussDBPLSQLReproducer(bundle);

        assertEquals("_fn", reproducer.getBundle().getFunctionName());
        assertEquals("CREATE ...", reproducer.getBundle().getCreateFunctionSql());
        assertEquals("DECL ...", reproducer.getBundle().getDeclarativeSql());
    }
}
