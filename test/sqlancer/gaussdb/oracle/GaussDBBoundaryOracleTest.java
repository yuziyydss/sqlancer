package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.Randomly;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBFunction;
import sqlancer.gaussdb.gen.GaussDBBoundaryHelper;
import sqlancer.gaussdb.oracle.GaussDBBoundaryOracle.BoundaryBundle;

class GaussDBBoundaryOracleTest {

    @Test
    void testBoundaryBundleGeneration() {
        GaussDBColumn col = new GaussDBColumn("c0", GaussDBDataType.INT);
        GaussDBTable table = new GaussDBTable("t0", Collections.singletonList(col),
                Collections.emptyList(), false, true);

        GaussDBExpression funcExpr = new GaussDBFunction("nvl",
                List.of(GaussDBConstant.createIntConstant(0), GaussDBConstant.createIntConstant(100)),
                GaussDBDataType.INT);
        GaussDBExpression predExpr = GaussDBConstant.createTrue();

        BoundaryBundle bundle = GaussDBBoundaryOracle.buildBoundaryBundle(table, funcExpr, predExpr);
        assertNotNull(bundle);

        assertTrue(bundle.getDirectQuery().contains("SELECT nvl(0, 100) FROM t0 t WHERE TRUE"));
        assertTrue(bundle.getCteQuery().contains("WITH cte AS (SELECT * FROM t0) SELECT nvl(0, 100) FROM cte t WHERE TRUE"));
        assertTrue(bundle.getSubqueryQuery().contains("SELECT sub.val FROM (SELECT nvl(0, 100) AS val FROM t0 t WHERE TRUE) sub"));
    }

    @Test
    void testBoundaryConstants() {
        Randomly r = new Randomly();
        GaussDBConstant intBound = GaussDBBoundaryHelper.generateBoundaryConstant(r, GaussDBDataType.INT);
        assertNotNull(intBound);
        assertEquals(GaussDBDataType.INT, intBound.getExpressionType());

        GaussDBConstant floatBound = GaussDBBoundaryHelper.generateBoundaryConstant(r, GaussDBDataType.FLOAT);
        assertNotNull(floatBound);
        assertEquals(GaussDBDataType.FLOAT, floatBound.getExpressionType());

        GaussDBConstant textBound = GaussDBBoundaryHelper.generateBoundaryConstant(r, GaussDBDataType.TEXT);
        assertNotNull(textBound);
        assertEquals(GaussDBDataType.TEXT, textBound.getExpressionType());

        GaussDBConstant dateBound = GaussDBBoundaryHelper.generateBoundaryConstant(r, GaussDBDataType.DATE);
        assertNotNull(dateBound);
        assertEquals(GaussDBDataType.DATE, dateBound.getExpressionType());
    }

    @Test
    void testBoundaryFunctions() {
        Randomly r = new Randomly();
        GaussDBExpression intFunc = GaussDBBoundaryHelper.generateBoundaryFunction(r, GaussDBDataType.INT, Collections.emptyList());
        assertNotNull(intFunc);
        assertEquals(GaussDBDataType.INT, intFunc.getExpressionType());

        GaussDBExpression textFunc = GaussDBBoundaryHelper.generateBoundaryFunction(r, GaussDBDataType.TEXT, Collections.emptyList());
        assertNotNull(textFunc);
        assertEquals(GaussDBDataType.TEXT, textFunc.getExpressionType());
    }

    @Test
    void testAreEquivalent() {
        List<String> r1 = Arrays.asList("[0]", "[100]");
        List<String> r2 = Arrays.asList("[100]", "[0]");
        List<String> r3 = Arrays.asList("[0]", "[999]");

        assertTrue(GaussDBBoundaryOracle.areEquivalent(r1, r2));
        assertFalse(GaussDBBoundaryOracle.areEquivalent(r1, r3));
    }

    @Test
    void testReproducerProperties() {
        BoundaryBundle bundle = new BoundaryBundle("SELECT 1", "WITH cte ...", "SELECT sub.val ...");
        GaussDBBoundaryReproducer reproducer = new GaussDBBoundaryReproducer(bundle);

        assertEquals("SELECT 1", reproducer.getBundle().getDirectQuery());
        assertEquals("WITH cte ...", reproducer.getBundle().getCteQuery());
        assertEquals("SELECT sub.val ...", reproducer.getBundle().getSubqueryQuery());
    }
}
