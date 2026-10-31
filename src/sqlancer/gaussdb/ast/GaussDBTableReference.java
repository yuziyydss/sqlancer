package sqlancer.gaussdb.ast;

import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;

public final class GaussDBTableReference implements GaussDBExpression {

    private final GaussDBTable table;

    public GaussDBTableReference(GaussDBTable table) {
        this.table = table;
    }

    public GaussDBTable getTable() {
        return table;
    }

    @Override
    public String toString() {
        return table.getName();
    }

}
