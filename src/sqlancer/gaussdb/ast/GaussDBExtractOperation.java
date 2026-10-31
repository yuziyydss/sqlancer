package sqlancer.gaussdb.ast;

import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public final class GaussDBExtractOperation implements GaussDBExpression {

    private final String field;
    private final GaussDBExpression expression;

    public GaussDBExtractOperation(String field, GaussDBExpression expression) {
        this.field = field;
        this.expression = expression;
    }

    public String getField() {
        return field;
    }

    public GaussDBExpression getExpression() {
        return expression;
    }

    @Override
    public GaussDBDataType getExpressionType() {
        return GaussDBDataType.DECIMAL;
    }

}
