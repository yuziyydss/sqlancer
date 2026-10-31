package sqlancer.gaussdb.ast;

import sqlancer.common.ast.newast.Expression;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public interface GaussDBExpression extends Expression<GaussDBColumn> {

    default GaussDBDataType getExpressionType() {
        return null;
    }

}
