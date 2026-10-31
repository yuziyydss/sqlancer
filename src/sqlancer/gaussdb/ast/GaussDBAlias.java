package sqlancer.gaussdb.ast;

public final class GaussDBAlias implements GaussDBExpression {

    private final GaussDBExpression expression;
    private final String alias;

    public GaussDBAlias(GaussDBExpression expression, String alias) {
        this.expression = expression;
        this.alias = alias;
    }

    public GaussDBExpression getExpression() {
        return expression;
    }

    public String getAlias() {
        return alias;
    }

}
