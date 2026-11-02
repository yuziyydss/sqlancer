package sqlancer.gaussdb.ast;

import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public final class GaussDBExists implements GaussDBExpression {

    private final GaussDBExpression subquery;
    private final boolean negated;

    public GaussDBExists(GaussDBExpression subquery, boolean negated) {
        this.subquery = subquery;
        this.negated = negated;
    }

    public GaussDBExpression getSubquery() {
        return subquery;
    }

    public boolean isNegated() {
        return negated;
    }

    @Override
    public GaussDBDataType getExpressionType() {
        return GaussDBDataType.BOOLEAN;
    }

}
