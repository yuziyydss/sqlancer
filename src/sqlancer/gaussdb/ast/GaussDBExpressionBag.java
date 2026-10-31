package sqlancer.gaussdb.ast;

import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public final class GaussDBExpressionBag implements GaussDBExpression {

    private GaussDBExpression innerExpression;

    public GaussDBExpressionBag(GaussDBExpression innerExpression) {
        this.innerExpression = innerExpression;
    }

    public void updateInnerExpression(GaussDBExpression innerExpression) {
        this.innerExpression = innerExpression;
    }

    public GaussDBExpression getInnerExpression() {
        return innerExpression;
    }

    @Override
    public GaussDBDataType getExpressionType() {
        return innerExpression == null ? null : innerExpression.getExpressionType();
    }

}
