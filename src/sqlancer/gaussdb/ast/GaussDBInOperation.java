package sqlancer.gaussdb.ast;

import java.util.List;

import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public final class GaussDBInOperation implements GaussDBExpression {

    private final GaussDBExpression expression;
    private final List<GaussDBExpression> list;
    private final boolean negated;

    public GaussDBInOperation(GaussDBExpression expression, List<GaussDBExpression> list, boolean negated) {
        this.expression = expression;
        this.list = list;
        this.negated = negated;
    }

    public GaussDBExpression getExpression() {
        return expression;
    }

    public List<GaussDBExpression> getList() {
        return list;
    }

    public boolean isNegated() {
        return negated;
    }

    @Override
    public GaussDBDataType getExpressionType() {
        return GaussDBDataType.BOOLEAN;
    }

}
