package sqlancer.gaussdb.oracle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import sqlancer.Randomly;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.ast.GaussDBBetweenOperation;
import sqlancer.gaussdb.ast.GaussDBBinaryOperation;
import sqlancer.gaussdb.ast.GaussDBCaseOperation;
import sqlancer.gaussdb.ast.GaussDBCastOperation;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBFunction;
import sqlancer.gaussdb.ast.GaussDBInOperation;
import sqlancer.gaussdb.ast.GaussDBUnaryOperation;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

/**
 * Equivalent Expression Transformation (EET, OSDI'24) rules for GaussDB.
 *
 * <p>
 * Given an expression {@code e} and an arbitrary boolean expression {@code q}, the following rewrites preserve the
 * value of {@code e} under SQL three-valued logic:
 * </p>
 * <ul>
 * <li>{@link Rule#CASE_SAME_BRANCHES}: {@code CASE WHEN q THEN e ELSE e END}</li>
 * <li>{@link Rule#CASE_FALSE_THEN_RANDOM}: {@code CASE WHEN contradiction(q) THEN r ELSE e END}</li>
 * <li>{@link Rule#CASE_TRUE_ELSE_RANDOM}: {@code CASE WHEN tautology(q) THEN e ELSE r END}</li>
 * <li>{@link Rule#BOOL_AND_TRUE} (boolean {@code e} only): {@code e AND tautology(q)}</li>
 * <li>{@link Rule#BOOL_OR_FALSE} (boolean {@code e} only): {@code e OR contradiction(q)}</li>
 * </ul>
 * <p>
 * where {@code tautology(q) = (q OR NOT q OR q IS NULL)} is always TRUE, {@code contradiction(q) = (q AND NOT q AND q
 * IS NOT NULL)} is always FALSE, and {@code r} is a random constant cast to the type of {@code e} so that the CASE
 * result type stays unchanged. The rewrite can be applied to the whole expression or to a randomly chosen
 * sub-expression.
 * </p>
 */
public final class GaussDBEETTransformer {

    public enum Rule {
        CASE_SAME_BRANCHES, CASE_FALSE_THEN_RANDOM, CASE_TRUE_ELSE_RANDOM, BOOL_AND_TRUE, BOOL_OR_FALSE;

        static List<Rule> applicableRules(GaussDBDataType type) {
            List<Rule> rules = new ArrayList<>();
            rules.add(CASE_SAME_BRANCHES);
            if (type != null) {
                rules.add(CASE_FALSE_THEN_RANDOM);
                rules.add(CASE_TRUE_ELSE_RANDOM);
            }
            if (type == GaussDBDataType.BOOLEAN) {
                rules.add(BOOL_AND_TRUE);
                rules.add(BOOL_OR_FALSE);
            }
            return rules;
        }
    }

    private GaussDBEETTransformer() {
    }

    // Applies a random applicable EET rule either to the root expression or to a randomly selected sub-expression.
    public static GaussDBExpression transform(GaussDBExpression expression, GaussDBExpression condition,
            Randomly randomly) {
        if (Randomly.getBoolean()) {
            return applyRandomRule(expression, condition, randomly);
        }
        int nodeCount = countNodes(expression);
        int target = (int) Randomly.getNotCachedInteger(0, nodeCount);
        return rewriteNode(expression, new int[] { target }, condition, randomly);
    }

    static GaussDBExpression applyRandomRule(GaussDBExpression expression, GaussDBExpression condition,
            Randomly randomly) {
        Rule rule = Randomly.fromList(Rule.applicableRules(expression.getExpressionType()));
        return applyRule(rule, expression, condition, randomly);
    }

    static GaussDBExpression applyRule(Rule rule, GaussDBExpression expression, GaussDBExpression condition,
            Randomly randomly) {
        GaussDBDataType type = expression.getExpressionType();
        switch (rule) {
        case CASE_SAME_BRANCHES:
            return new GaussDBCaseOperation(Collections.singletonList(condition), Collections.singletonList(expression),
                    expression, type);
        case CASE_FALSE_THEN_RANDOM:
            requireType(rule, type);
            return new GaussDBCaseOperation(Collections.singletonList(contradiction(condition)),
                    Collections.singletonList(randomValue(type, randomly)), expression, type);
        case CASE_TRUE_ELSE_RANDOM:
            requireType(rule, type);
            return new GaussDBCaseOperation(Collections.singletonList(tautology(condition)),
                    Collections.singletonList(expression), randomValue(type, randomly), type);
        case BOOL_AND_TRUE:
            requireBoolean(rule, type);
            return new GaussDBBinaryOperation(expression, tautology(condition), GaussDBBinaryOperation.Operator.AND,
                    GaussDBDataType.BOOLEAN);
        case BOOL_OR_FALSE:
            requireBoolean(rule, type);
            return new GaussDBBinaryOperation(expression, contradiction(condition), GaussDBBinaryOperation.Operator.OR,
                    GaussDBDataType.BOOLEAN);
        default:
            throw new AssertionError(rule);
        }
    }

    // (q OR NOT q OR q IS NULL): TRUE for every value of q.
    static GaussDBExpression tautology(GaussDBExpression condition) {
        GaussDBExpression notCondition = new GaussDBUnaryOperation(condition, GaussDBUnaryOperation.Operator.NOT,
                GaussDBDataType.BOOLEAN);
        GaussDBExpression isNull = new GaussDBUnaryOperation(condition, GaussDBUnaryOperation.Operator.IS_NULL,
                GaussDBDataType.BOOLEAN);
        return new GaussDBBinaryOperation(
                new GaussDBBinaryOperation(condition, notCondition, GaussDBBinaryOperation.Operator.OR,
                        GaussDBDataType.BOOLEAN),
                isNull, GaussDBBinaryOperation.Operator.OR, GaussDBDataType.BOOLEAN);
    }

    // (q AND NOT q AND q IS NOT NULL): FALSE for every value of q.
    static GaussDBExpression contradiction(GaussDBExpression condition) {
        GaussDBExpression notCondition = new GaussDBUnaryOperation(condition, GaussDBUnaryOperation.Operator.NOT,
                GaussDBDataType.BOOLEAN);
        GaussDBExpression isNotNull = new GaussDBUnaryOperation(condition, GaussDBUnaryOperation.Operator.IS_NOT_NULL,
                GaussDBDataType.BOOLEAN);
        return new GaussDBBinaryOperation(
                new GaussDBBinaryOperation(condition, notCondition, GaussDBBinaryOperation.Operator.AND,
                        GaussDBDataType.BOOLEAN),
                isNotNull, GaussDBBinaryOperation.Operator.AND, GaussDBDataType.BOOLEAN);
    }

    private static GaussDBExpression randomValue(GaussDBDataType type, Randomly randomly) {
        GaussDBExpression value = Randomly.getBooleanWithRatherLowProbability() ? GaussDBConstant.createNullConstant()
                : GaussDBExpressionGenerator.generateConstant(randomly, type);
        // Cast to the target type so that the CASE result type is determined by the original expression only.
        return new GaussDBCastOperation(value, type);
    }

    private static void requireType(Rule rule, GaussDBDataType type) {
        if (type == null) {
            throw new IllegalArgumentException(rule + " requires a typed expression");
        }
    }

    private static void requireBoolean(Rule rule, GaussDBDataType type) {
        if (type != GaussDBDataType.BOOLEAN) {
            throw new IllegalArgumentException(rule + " requires a boolean expression");
        }
    }

    // Counts the nodes that rewriteNode can visit (pre-order).
    static int countNodes(GaussDBExpression expression) {
        int count = 1;
        for (GaussDBExpression child : children(expression)) {
            count += countNodes(child);
        }
        return count;
    }

    private static List<GaussDBExpression> children(GaussDBExpression expression) {
        List<GaussDBExpression> children = new ArrayList<>();
        if (expression instanceof GaussDBBinaryOperation) {
            GaussDBBinaryOperation op = (GaussDBBinaryOperation) expression;
            children.add(op.getLeft());
            children.add(op.getRight());
        } else if (expression instanceof GaussDBUnaryOperation) {
            children.add(((GaussDBUnaryOperation) expression).getExpression());
        } else if (expression instanceof GaussDBCaseOperation) {
            GaussDBCaseOperation op = (GaussDBCaseOperation) expression;
            children.addAll(op.getConditions());
            children.addAll(op.getResults());
            if (op.getElseExpression() != null) {
                children.add(op.getElseExpression());
            }
        } else if (expression instanceof GaussDBCastOperation) {
            children.add(((GaussDBCastOperation) expression).getExpression());
        } else if (expression instanceof GaussDBFunction) {
            children.addAll(((GaussDBFunction) expression).getArguments());
        } else if (expression instanceof GaussDBBetweenOperation) {
            GaussDBBetweenOperation op = (GaussDBBetweenOperation) expression;
            children.add(op.getExpression());
            children.add(op.getLeft());
            children.add(op.getRight());
        } else if (expression instanceof GaussDBInOperation) {
            GaussDBInOperation op = (GaussDBInOperation) expression;
            children.add(op.getExpression());
            children.addAll(op.getList());
        }
        return children;
    }

    // Rebuilds expression, replacing the node whose pre-order index equals remaining[0] by an equivalent expression.
    // Nodes that are not descended into (subqueries, aggregates, constants, columns) are treated as leaves.
    private static GaussDBExpression rewriteNode(GaussDBExpression expression, int[] remaining,
            GaussDBExpression condition, Randomly randomly) {
        if (remaining[0] == 0) {
            remaining[0] = -1;
            return applyRandomRule(expression, condition, randomly);
        }
        remaining[0]--;
        if (remaining[0] < 0) {
            return expression;
        }
        if (expression instanceof GaussDBBinaryOperation) {
            GaussDBBinaryOperation op = (GaussDBBinaryOperation) expression;
            GaussDBExpression left = rewriteNode(op.getLeft(), remaining, condition, randomly);
            GaussDBExpression right = rewriteNode(op.getRight(), remaining, condition, randomly);
            return new GaussDBBinaryOperation(left, right, op.getOperator(), op.getExpressionType());
        } else if (expression instanceof GaussDBUnaryOperation) {
            GaussDBUnaryOperation op = (GaussDBUnaryOperation) expression;
            return new GaussDBUnaryOperation(rewriteNode(op.getExpression(), remaining, condition, randomly),
                    op.getOperator(), op.getExpressionType());
        } else if (expression instanceof GaussDBCaseOperation) {
            GaussDBCaseOperation op = (GaussDBCaseOperation) expression;
            List<GaussDBExpression> conditions = rewriteAll(op.getConditions(), remaining, condition, randomly);
            List<GaussDBExpression> results = rewriteAll(op.getResults(), remaining, condition, randomly);
            GaussDBExpression elseExpression = op.getElseExpression() == null ? null
                    : rewriteNode(op.getElseExpression(), remaining, condition, randomly);
            return new GaussDBCaseOperation(conditions, results, elseExpression, op.getExpressionType());
        } else if (expression instanceof GaussDBCastOperation) {
            GaussDBCastOperation op = (GaussDBCastOperation) expression;
            return new GaussDBCastOperation(rewriteNode(op.getExpression(), remaining, condition, randomly),
                    op.getExpressionType());
        } else if (expression instanceof GaussDBFunction) {
            GaussDBFunction op = (GaussDBFunction) expression;
            return new GaussDBFunction(op.getFunctionName(),
                    rewriteAll(op.getArguments(), remaining, condition, randomly), op.getExpressionType());
        } else if (expression instanceof GaussDBBetweenOperation) {
            GaussDBBetweenOperation op = (GaussDBBetweenOperation) expression;
            GaussDBExpression expr = rewriteNode(op.getExpression(), remaining, condition, randomly);
            GaussDBExpression left = rewriteNode(op.getLeft(), remaining, condition, randomly);
            GaussDBExpression right = rewriteNode(op.getRight(), remaining, condition, randomly);
            return new GaussDBBetweenOperation(expr, left, right, op.isNegated(), op.isSymmetric());
        } else if (expression instanceof GaussDBInOperation) {
            GaussDBInOperation op = (GaussDBInOperation) expression;
            GaussDBExpression expr = rewriteNode(op.getExpression(), remaining, condition, randomly);
            return new GaussDBInOperation(expr, rewriteAll(op.getList(), remaining, condition, randomly),
                    op.isNegated());
        }
        return expression;
    }

    private static List<GaussDBExpression> rewriteAll(List<GaussDBExpression> expressions, int[] remaining,
            GaussDBExpression condition, Randomly randomly) {
        List<GaussDBExpression> rewritten = new ArrayList<>(expressions.size());
        for (GaussDBExpression expression : expressions) {
            rewritten.add(rewriteNode(expression, remaining, condition, randomly));
        }
        return rewritten;
    }

}
