package sqlancer.gaussdb.ast;

public final class GaussDBWithClause implements GaussDBExpression {

    private final String alias;
    private final GaussDBExpression expression;

    public GaussDBWithClause(String alias, GaussDBExpression expression) {
        this.alias = alias;
        this.expression = expression;
    }

    public String getAlias() {
        return alias;
    }

    public GaussDBExpression getExpression() {
        return expression;
    }

}
