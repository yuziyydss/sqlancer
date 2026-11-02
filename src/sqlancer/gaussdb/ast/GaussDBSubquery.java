package sqlancer.gaussdb.ast;

public final class GaussDBSubquery implements GaussDBExpression {

    private final GaussDBSelect select;
    private final GaussDBExpression expression;

    public GaussDBSubquery(GaussDBSelect select) {
        this.select = select;
        this.expression = select;
    }

    public GaussDBSubquery(GaussDBExpression expression) {
        this.select = expression instanceof GaussDBSelect ? (GaussDBSelect) expression : null;
        this.expression = expression;
    }

    public GaussDBSelect getSelect() {
        return select;
    }

    public GaussDBExpression getExpression() {
        return expression;
    }

}
