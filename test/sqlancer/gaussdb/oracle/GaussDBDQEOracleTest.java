package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.GaussDBToStringVisitor;
import sqlancer.gaussdb.ast.GaussDBBinaryOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBOrderByTerm;
import sqlancer.gaussdb.oracle.GaussDBDQEOracle.DQEQueryPlan;

class GaussDBDQEOracleTest {

    private final GaussDBColumn intCol = new GaussDBColumn("c0", GaussDBDataType.INT);
    private final GaussDBColumn textCol = new GaussDBColumn("c1", GaussDBDataType.TEXT);
    private final GaussDBTable table = new GaussDBTable("t0", List.of(intCol, textCol), null, false, true);

    @Test
    void createPlanBuildsWorkTableAndReturningQueries() {
        GaussDBExpression predicate = new GaussDBBinaryOperation(new GaussDBColumnReference(intCol, "t"),
                GaussDBConstant.createIntConstant(10), GaussDBBinaryOperation.Operator.GREATER,
                GaussDBDataType.BOOLEAN);

        DQEQueryPlan plan = GaussDBDQEOracle.createPlan(table, predicate, "ustore", intCol);

        assertEquals(3, plan.getSetupSql().size());
        assertEquals("DROP TABLE IF EXISTS t0_dqe_work CASCADE", plan.getSetupSql().get(0));
        assertTrue(plan.getSetupSql().get(1).contains("WITH (storage_type = ustore)"));
        assertEquals("CREATE INDEX t0_dqe_work_idx ON t0_dqe_work USING ubtree (c0)", plan.getSetupSql().get(2));
        assertEquals("SELECT dqe_rid FROM t0_dqe_work AS t WHERE ((t.c0) > (10))", plan.getSelectSql());
        assertEquals("UPDATE t0_dqe_work AS t SET dqe_updated = 1 WHERE ((t.c0) > (10)) RETURNING dqe_rid",
                plan.getUpdateSql());
        assertEquals("SELECT dqe_rid FROM t0_dqe_work WHERE dqe_updated = 1", plan.getSelectUpdatedSql());
        assertEquals("DELETE FROM t0_dqe_work AS t WHERE ((t.c0) > (10)) RETURNING dqe_rid", plan.getDeleteSql());
    }

    @Test
    void areEquivalentChecksMultisetEquality() {
        assertTrue(GaussDBDQEOracle.areEquivalent(Arrays.asList("[1]", "[3]"), Arrays.asList("[3]", "[1]")));
        assertFalse(GaussDBDQEOracle.areEquivalent(Arrays.asList("[1]"), Arrays.asList("[1]", "[2]")));
    }

    @Test
    void orderByTermRendersOrderAndNullsPlacement() {
        GaussDBOrderByTerm term = new GaussDBOrderByTerm(new GaussDBColumnReference(intCol, "t"),
                GaussDBOrderByTerm.Order.DESC, GaussDBOrderByTerm.NullsOrder.NULLS_LAST);
        assertEquals("t.c0 DESC NULLS LAST", GaussDBToStringVisitor.asString(term));
    }

}
