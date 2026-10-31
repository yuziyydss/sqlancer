package sqlancer.gaussdb.ast;

import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public final class GaussDBColumnReference implements GaussDBExpression {

    private final GaussDBColumn column;
    private final String tableAlias;

    public GaussDBColumnReference(GaussDBColumn column) {
        this(column, null);
    }

    public GaussDBColumnReference(GaussDBColumn column, String tableAlias) {
        this.column = column;
        this.tableAlias = tableAlias;
    }

    public GaussDBColumn getColumn() {
        return column;
    }

    public String getTableAlias() {
        return tableAlias;
    }

    @Override
    public GaussDBDataType getExpressionType() {
        return column.getType();
    }

    @Override
    public String toString() {
        return tableAlias == null ? column.getFullQualifiedName() : tableAlias + "." + column.getName();
    }

}
