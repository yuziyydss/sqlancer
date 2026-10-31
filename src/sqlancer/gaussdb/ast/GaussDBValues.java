package sqlancer.gaussdb.ast;

import java.util.List;

public final class GaussDBValues implements GaussDBExpression {

    private final List<GaussDBExpression> rows;

    public GaussDBValues(List<GaussDBExpression> rows) {
        this.rows = rows;
    }

    public List<GaussDBExpression> getRows() {
        return rows;
    }

}
