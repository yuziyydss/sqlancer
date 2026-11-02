package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.ast.GaussDBBinaryOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.oracle.GaussDBDQPOracle.DQPPlanBundle;
import sqlancer.gaussdb.oracle.GaussDBNoRECOracle.NoRECQueryPair;
import sqlancer.gaussdb.oracle.GaussDBTLPOracle.TLPQueryPair;

class GaussDBNoRECTLPDQPOracleTest {

    private final GaussDBColumn intCol = new GaussDBColumn("c0", GaussDBDataType.INT);
    private final GaussDBColumn textCol = new GaussDBColumn("c1", GaussDBDataType.TEXT);
    private final GaussDBTable table = new GaussDBTable("t0", List.of(intCol, textCol), null, false, true);

    @Test
    void noRecBuildsOptimizedCountAndUnoptimizedSumQuery() {
        GaussDBExpression predicate = new GaussDBBinaryOperation(new GaussDBColumnReference(intCol, "t"),
                GaussDBConstant.createIntConstant(5), GaussDBBinaryOperation.Operator.EQUALS,
                GaussDBDataType.BOOLEAN);
        NoRECQueryPair pair = GaussDBNoRECOracle.createSingleTablePair(table, predicate);

        assertEquals("SELECT COUNT(*) FROM t0 AS t WHERE ((t.c0) = (5))", pair.getOptimizedQuery());
        assertEquals(
                "SELECT COALESCE(SUM(c), 0) FROM (SELECT CAST(((((t.c0) = (5))) IS TRUE) AS BIGINT) AS c FROM t0 AS t) AS norec_sub",
                pair.getUnoptimizedQuery());
    }

    @Test
    void tlpBuildsThreeValuedUnionQueriesForWhereAndDistinct() {
        GaussDBExpression predicate = new GaussDBBinaryOperation(new GaussDBColumnReference(intCol, "t"),
                GaussDBConstant.createIntConstant(5), GaussDBBinaryOperation.Operator.GREATER,
                GaussDBDataType.BOOLEAN);

        TLPQueryPair wherePair = GaussDBTLPOracle.createWherePair(table, predicate);
        assertEquals("SELECT * FROM t0 AS t", wherePair.getOriginalQuery());
        assertTrue(wherePair.getPartitionedQuery().contains("IS TRUE"));
        assertTrue(wherePair.getPartitionedQuery().contains("IS FALSE"));
        assertTrue(wherePair.getPartitionedQuery().contains("IS NULL"));
        assertTrue(wherePair.getPartitionedQuery().contains("UNION ALL"));

        TLPQueryPair distinctPair = GaussDBTLPOracle.createDistinctPair(table, predicate);
        assertEquals("SELECT DISTINCT * FROM t0 AS t", distinctPair.getOriginalQuery());
    }

    @Test
    void dqpBuildsThreePlanVariantsWithOptimizerHintsAndGucs() {
        DQPPlanBundle joinBundle = GaussDBDQPOracle.createBundle(
                "SELECT * FROM t0 AS l INNER JOIN t1 AS r ON ((l.c0) = (r.c0))", true);

        assertEquals(3, joinBundle.getVariants().size());
        assertEquals(
                "SELECT /*+ NestLoop(l r) IndexScan(l) */ * FROM t0 AS l INNER JOIN t1 AS r ON ((l.c0) = (r.c0))",
                joinBundle.getVariants().get(0).getSql());
        assertEquals(
                "SELECT /*+ HashJoin(l r) TableScan(l) */ * FROM t0 AS l INNER JOIN t1 AS r ON ((l.c0) = (r.c0))",
                joinBundle.getVariants().get(1).getSql());
        assertEquals("SELECT /*+ MergeJoin(l r) */ * FROM t0 AS l INNER JOIN t1 AS r ON ((l.c0) = (r.c0))",
                joinBundle.getVariants().get(2).getSql());
    }

}
