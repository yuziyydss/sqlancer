package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.ast.GaussDBBinaryOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBJoin;
import sqlancer.gaussdb.oracle.GaussDBCERTOracle.CERTQueryPair;

class GaussDBCERTOracleTest {

    @Test
    void testCERTQueryGenerationSingleTable() {
        GaussDBColumn col0 = new GaussDBColumn("c0", GaussDBDataType.INT);
        GaussDBTable table = new GaussDBTable("t0", Collections.singletonList(col0), Collections.emptyList(), false, true);

        GaussDBColumnReference colRef = new GaussDBColumnReference(col0, "t");
        GaussDBBinaryOperation pred1 = new GaussDBBinaryOperation(colRef,
                GaussDBConstant.createIntConstant(10), GaussDBBinaryOperation.Operator.GREATER,
                GaussDBDataType.BOOLEAN);
        GaussDBBinaryOperation pred2 = new GaussDBBinaryOperation(colRef,
                GaussDBConstant.createIntConstant(100), GaussDBBinaryOperation.Operator.LESS,
                GaussDBDataType.BOOLEAN);

        CERTQueryPair pair = GaussDBCERTOracle.createSingleTablePair(table, pred1, pred2);
        assertNotNull(pair);
        assertEquals("SELECT * FROM t0 AS t WHERE ((t.c0) > (10))", pair.getBaseQuery());
        assertEquals("SELECT * FROM t0 AS t WHERE ((((t.c0) > (10))) AND (((t.c0) < (100))))", pair.getRestrictedQuery());
    }

    @Test
    void testCERTQueryGenerationJoin() {
        GaussDBColumn colL = new GaussDBColumn("id", GaussDBDataType.INT);
        GaussDBColumn colR = new GaussDBColumn("id", GaussDBDataType.INT);
        GaussDBTable left = new GaussDBTable("tl", Collections.singletonList(colL), Collections.emptyList(), false, true);
        GaussDBTable right = new GaussDBTable("tr", Collections.singletonList(colR), Collections.emptyList(), false, true);

        GaussDBBinaryOperation onPred = new GaussDBBinaryOperation(
                new GaussDBColumnReference(colL, "l"),
                new GaussDBColumnReference(colR, "r"),
                GaussDBBinaryOperation.Operator.EQUALS,
                GaussDBDataType.BOOLEAN);

        GaussDBBinaryOperation pred1 = new GaussDBBinaryOperation(
                new GaussDBColumnReference(colL, "l"),
                GaussDBConstant.createIntConstant(0),
                GaussDBBinaryOperation.Operator.GREATER,
                GaussDBDataType.BOOLEAN);

        GaussDBBinaryOperation pred2 = new GaussDBBinaryOperation(
                new GaussDBColumnReference(colR, "r"),
                GaussDBConstant.createIntConstant(50),
                GaussDBBinaryOperation.Operator.LESS,
                GaussDBDataType.BOOLEAN);

        CERTQueryPair pair = GaussDBCERTOracle.createJoinPair(left, right, GaussDBJoin.JoinType.INNER, onPred, pred1, pred2);
        assertNotNull(pair);
        assertEquals("SELECT * FROM tl AS l JOIN tr AS r ON ((l.id) = (r.id)) WHERE ((l.id) > (0))", pair.getBaseQuery());
        assertEquals("SELECT * FROM tl AS l JOIN tr AS r ON ((l.id) = (r.id)) WHERE ((((l.id) > (0))) AND (((r.id) < (50))))", pair.getRestrictedQuery());
    }

    @Test
    void testCERTReproducerProperties() {
        GaussDBCERTReproducer reproducer = new GaussDBCERTReproducer(
                "SELECT * FROM t WHERE c0 > 10",
                "SELECT * FROM t WHERE (c0 > 10) AND (c0 < 100)",
                50, 120);

        assertEquals("SELECT * FROM t WHERE c0 > 10", reproducer.getQuery1());
        assertEquals("SELECT * FROM t WHERE (c0 > 10) AND (c0 < 100)", reproducer.getQuery2());
        assertEquals(50, reproducer.getEstimated1());
        assertEquals(120, reproducer.getEstimated2());
    }
}
