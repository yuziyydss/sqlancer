package sqlancer.gaussdb.ast;

public final class GaussDBSubquery implements GaussDBExpression {

    private final GaussDBSelect select;

    public GaussDBSubquery(GaussDBSelect select) {
        this.select = select;
    }

    public GaussDBSelect getSelect() {
        return select;
    }

}
