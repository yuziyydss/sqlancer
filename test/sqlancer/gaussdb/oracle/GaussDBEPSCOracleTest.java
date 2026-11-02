package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.Randomly;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.ast.GaussDBBinaryOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBFunction;
import sqlancer.gaussdb.ast.GaussDBSelect;
import sqlancer.gaussdb.oracle.GaussDBEPSCOracle.PreparedQueryPlan;

class GaussDBEPSCOracleTest {

    private final GaussDBColumn intCol = new GaussDBColumn("c0", GaussDBDataType.INT);
    private final GaussDBColumn textCol = new GaussDBColumn("c1", GaussDBDataType.TEXT);
    private final GaussDBTable table = new GaussDBTable("t0", List.of(intCol, textCol), null, false, true);

    @Test
    void parameterizeConstantsReplacesNonNullTypedConstantsWithPositionalPlaceholders() {
        GaussDBExpression left = new GaussDBBinaryOperation(new GaussDBColumnReference(intCol, "t"),
                GaussDBConstant.createIntConstant(42), GaussDBBinaryOperation.Operator.EQUALS,
                GaussDBDataType.BOOLEAN);
        GaussDBExpression right = new GaussDBBinaryOperation(new GaussDBColumnReference(textCol, "t"),
                GaussDBConstant.createTextConstant("abc"), GaussDBBinaryOperation.Operator.EQUALS,
                GaussDBDataType.BOOLEAN);
        GaussDBExpression predicate = new GaussDBBinaryOperation(left, right, GaussDBBinaryOperation.Operator.AND,
                GaussDBDataType.BOOLEAN);

        List<GaussDBConstant> params = new ArrayList<>();
        GaussDBExpression parameterized = GaussDBEPSCOracle.parameterizeConstants(predicate, params);

        GaussDBSelect originalSelect = GaussDBEETOracle.selectStarWhere(table, predicate);
        GaussDBSelect preparedSelect = GaussDBEETOracle.selectStarWhere(table, parameterized);
        PreparedQueryPlan plan = GaussDBEPSCOracle.createPlan(originalSelect, preparedSelect, params);

        assertNotNull(plan);
        assertEquals(2, plan.getParameters().size());
        assertEquals("SELECT * FROM t0 AS t WHERE ((((t.c0) = (42))) AND (((t.c1) = ('abc'))))",
                plan.getOriginalQuery());
        assertEquals(
                "PREPARE sqlancer_epsc_stmt(BIGINT, TEXT) AS "
                        + "SELECT * FROM t0 AS t WHERE ((((t.c0) = ($1))) AND (((t.c1) = ($2))))",
                plan.getPrepareSql());
        assertEquals("EXECUTE sqlancer_epsc_stmt(42, 'abc')", plan.getExecuteSql());
        assertEquals("DEALLOCATE PREPARE sqlancer_epsc_stmt", plan.getDeallocateSql());
    }

    @Test
    void parameterizeConstantsPreservesNullConstantsAndDatePartUnit() {
        GaussDBExpression datePart = new GaussDBFunction("date_part",
                List.of(GaussDBConstant.createTextConstant("day"),
                        GaussDBConstant.createTimestampConstant("2020-01-02 03:04:05")),
                GaussDBDataType.DECIMAL);
        GaussDBExpression eqNull = new GaussDBBinaryOperation(datePart, GaussDBConstant.createNullConstant(),
                GaussDBBinaryOperation.Operator.EQUALS, GaussDBDataType.BOOLEAN);

        List<GaussDBConstant> params = new ArrayList<>();
        GaussDBExpression parameterized = GaussDBEPSCOracle.parameterizeConstants(eqNull, params);
        PreparedQueryPlan plan = GaussDBEPSCOracle.createPlan(GaussDBEETOracle.selectStarWhere(table, eqNull),
                GaussDBEETOracle.selectStarWhere(table, parameterized), params);

        assertNotNull(plan);
        assertEquals(1, plan.getParameters().size());
        assertEquals(
                "PREPARE sqlancer_epsc_stmt(TIMESTAMP) AS "
                        + "SELECT * FROM t0 AS t WHERE ((date_part('day', $1)) = (NULL))",
                plan.getPrepareSql());
        assertEquals("EXECUTE sqlancer_epsc_stmt(TIMESTAMP '2020-01-02 03:04:05')", plan.getExecuteSql());
    }

    @Test
    void ensureTypedConstantAddsTrivialConstantComparisonWhenNoConstantExists() {
        GaussDBExpression colOnly = new GaussDBBinaryOperation(new GaussDBColumnReference(intCol, "t"),
                new GaussDBColumnReference(intCol, "t"), GaussDBBinaryOperation.Operator.EQUALS,
                GaussDBDataType.BOOLEAN);

        List<GaussDBConstant> emptyParams = new ArrayList<>();
        GaussDBEPSCOracle.parameterizeConstants(colOnly, emptyParams);
        assertNull(GaussDBEPSCOracle.createPlan(colOnly, colOnly, emptyParams));

        GaussDBExpression enriched = GaussDBEPSCOracle.ensureTypedConstant(colOnly, new Randomly(7L));
        List<GaussDBConstant> params = new ArrayList<>();
        GaussDBExpression parameterized = GaussDBEPSCOracle.parameterizeConstants(enriched, params);
        PreparedQueryPlan plan = GaussDBEPSCOracle.createPlan(enriched, parameterized, params);
        assertNotNull(plan);
        assertEquals(2, plan.getParameters().size());
    }

}
