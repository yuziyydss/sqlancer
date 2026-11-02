package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.Randomly;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBToStringVisitor;
import sqlancer.gaussdb.ast.GaussDBBetweenOperation;
import sqlancer.gaussdb.ast.GaussDBBinaryOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBFunction;
import sqlancer.gaussdb.ast.GaussDBInOperation;
import sqlancer.gaussdb.ast.GaussDBUnaryOperation;
import sqlancer.gaussdb.oracle.GaussDBEETTransformer.Rule;

class GaussDBEETTransformerTest {

    private final GaussDBColumn intColumn = new GaussDBColumn("c0", GaussDBDataType.INT);
    private final GaussDBColumn boolColumn = new GaussDBColumn("c1", GaussDBDataType.BOOLEAN);

    @Test
    void tautologyAndContradictionRenderExpectedThreeValuedLogicForms() {
        GaussDBExpression q = new GaussDBColumnReference(boolColumn, "t");

        assertEquals("((((t.c1) OR ((NOT (t.c1))))) OR (((t.c1) IS NULL)))",
                GaussDBToStringVisitor.asString(GaussDBEETTransformer.tautology(q)));
        assertEquals("((((t.c1) AND ((NOT (t.c1))))) AND (((t.c1) IS NOT NULL)))",
                GaussDBToStringVisitor.asString(GaussDBEETTransformer.contradiction(q)));
    }

    @Test
    void applicableRulesFilterByExpressionType() {
        assertEquals(List.of(Rule.CASE_SAME_BRANCHES), Rule.applicableRules(null));
        assertEquals(List.of(Rule.CASE_SAME_BRANCHES, Rule.CASE_FALSE_THEN_RANDOM, Rule.CASE_TRUE_ELSE_RANDOM),
                Rule.applicableRules(GaussDBDataType.INT));
        assertEquals(List.of(Rule.CASE_SAME_BRANCHES, Rule.CASE_FALSE_THEN_RANDOM, Rule.CASE_TRUE_ELSE_RANDOM,
                Rule.BOOL_AND_TRUE, Rule.BOOL_OR_FALSE), Rule.applicableRules(GaussDBDataType.BOOLEAN));
    }

    @Test
    void applyRuleProducesExpectedEquivalentForms() {
        GaussDBExpression intExpr = new GaussDBColumnReference(intColumn, "t");
        GaussDBExpression boolExpr = new GaussDBColumnReference(boolColumn, "t");
        GaussDBExpression q = GaussDBConstant.createBooleanConstant(false);
        Randomly randomly = new Randomly(1L);

        assertEquals("CASE WHEN FALSE THEN t.c0 ELSE t.c0 END", GaussDBToStringVisitor
                .asString(GaussDBEETTransformer.applyRule(Rule.CASE_SAME_BRANCHES, intExpr, q, randomly)));

        String falseThenRandom = GaussDBToStringVisitor
                .asString(GaussDBEETTransformer.applyRule(Rule.CASE_FALSE_THEN_RANDOM, intExpr, q, randomly));
        assertTrue(falseThenRandom
                .startsWith("CASE WHEN ((((FALSE) AND ((NOT (FALSE))))) AND (((FALSE) IS NOT NULL))) THEN CAST("));
        assertTrue(falseThenRandom.endsWith(" AS BIGINT) ELSE t.c0 END"));

        String trueElseRandom = GaussDBToStringVisitor
                .asString(GaussDBEETTransformer.applyRule(Rule.CASE_TRUE_ELSE_RANDOM, intExpr, q, randomly));
        assertTrue(trueElseRandom
                .startsWith("CASE WHEN ((((FALSE) OR ((NOT (FALSE))))) OR (((FALSE) IS NULL))) THEN t.c0 ELSE CAST("));
        assertTrue(trueElseRandom.endsWith(" AS BIGINT) END"));

        assertEquals("((t.c1) AND (((((FALSE) OR ((NOT (FALSE))))) OR (((FALSE) IS NULL)))))", GaussDBToStringVisitor
                .asString(GaussDBEETTransformer.applyRule(Rule.BOOL_AND_TRUE, boolExpr, q, randomly)));
        assertEquals("((t.c1) OR (((((FALSE) AND ((NOT (FALSE))))) AND (((FALSE) IS NOT NULL)))))",
                GaussDBToStringVisitor
                        .asString(GaussDBEETTransformer.applyRule(Rule.BOOL_OR_FALSE, boolExpr, q, randomly)));
    }

    @Test
    void countNodesAndSubexpressionTransformCoverCompositeExpressions() {
        GaussDBExpression col = new GaussDBColumnReference(intColumn, "t");
        GaussDBExpression plus = new GaussDBBinaryOperation(col, GaussDBConstant.createIntConstant(1),
                GaussDBBinaryOperation.Operator.PLUS, GaussDBDataType.INT);
        GaussDBExpression neg = new GaussDBUnaryOperation(plus, GaussDBUnaryOperation.Operator.NEGATE,
                GaussDBDataType.INT);
        GaussDBExpression func = new GaussDBFunction("abs", List.of(neg), GaussDBDataType.INT);
        GaussDBExpression between = new GaussDBBetweenOperation(func, GaussDBConstant.createIntConstant(0),
                GaussDBConstant.createIntConstant(10), false, false);
        GaussDBExpression inOp = new GaussDBInOperation(col, List.of(GaussDBConstant.createIntConstant(1)), false);
        GaussDBExpression root = new GaussDBBinaryOperation(between, inOp, GaussDBBinaryOperation.Operator.AND,
                GaussDBDataType.BOOLEAN);

        assertEquals(12, GaussDBEETTransformer.countNodes(root));

        Randomly randomly = new Randomly(42L);
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(true);
        for (int i = 0; i < 30; i++) {
            GaussDBExpression transformed = GaussDBEETTransformer.transform(root, condition, randomly);
            assertEquals(GaussDBDataType.BOOLEAN, transformed.getExpressionType());
            String sql = GaussDBToStringVisitor.asString(transformed);
            assertTrue(sql.contains("t.c0"));
        }
    }

}
