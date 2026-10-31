package sqlancer.gaussdb.ast;

import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public final class GaussDBBetweenOperation implements GaussDBExpression {

    private final GaussDBExpression expression;
    private final GaussDBExpression left;
    private final GaussDBExpression right;
    private final boolean negated;
    private final boolean symmetric;

    public GaussDBBetweenOperation(GaussDBExpression expression, GaussDBExpression left, GaussDBExpression right,
            boolean negated, boolean symmetric) {
        this.expression = expression;
        this.left = left;
        this.right = right;
        this.negated = negated;
        this.symmetric = symmetric;
    }

    public GaussDBExpression getExpression() {
        return expression;
    }

    public GaussDBExpression getLeft() {
        return left;
    }

    public GaussDBExpression getRight() {
        return right;
    }

    public boolean isNegated() {
        return negated;
    }

    public boolean isSymmetric() {
        return symmetric;
    }

    @Override
    public GaussDBDataType getExpressionType() {
        return GaussDBDataType.BOOLEAN;
    }

}
