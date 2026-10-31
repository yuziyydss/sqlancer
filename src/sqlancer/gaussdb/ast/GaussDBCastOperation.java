package sqlancer.gaussdb.ast;

import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public final class GaussDBCastOperation implements GaussDBExpression {

    private final GaussDBExpression expression;
    private final GaussDBDataType type;

    public GaussDBCastOperation(GaussDBExpression expression, GaussDBDataType type) {
        this.expression = expression;
        this.type = type;
    }

    public GaussDBExpression getExpression() {
        return expression;
    }

    @Override
    public GaussDBDataType getExpressionType() {
        return type;
    }

}
