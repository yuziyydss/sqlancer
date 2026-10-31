package sqlancer.gaussdb.ast;

public final class GaussDBInSubquery implements GaussDBExpression {

    private final GaussDBExpression expression;
    private final GaussDBExpression subquery;
    private final boolean negated;

    public GaussDBInSubquery(GaussDBExpression expression, GaussDBExpression subquery, boolean negated) {
        this.expression = expression;
        this.subquery = subquery;
        this.negated = negated;
    }

    public GaussDBExpression getExpression() {
        return expression;
    }

    public GaussDBExpression getSubquery() {
        return subquery;
    }

    public boolean isNegated() {
        return negated;
    }

}
