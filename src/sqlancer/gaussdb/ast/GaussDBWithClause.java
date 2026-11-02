package sqlancer.gaussdb.ast;

public final class GaussDBWithClause implements GaussDBExpression {

    private final String alias;
    private final GaussDBExpression expression;
    private final boolean recursive;

    public GaussDBWithClause(String alias, GaussDBExpression expression) {
        this(alias, expression, false);
    }

    public GaussDBWithClause(String alias, GaussDBExpression expression, boolean recursive) {
        this.alias = alias;
        this.expression = expression;
        this.recursive = recursive;
    }

    public String getAlias() {
        return alias;
    }

    public GaussDBExpression getExpression() {
        return expression;
    }

    public boolean isRecursive() {
        return recursive;
    }

}
