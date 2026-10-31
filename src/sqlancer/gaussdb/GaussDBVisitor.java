package sqlancer.gaussdb;

import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBExpression;

public interface GaussDBVisitor {

    void visit(GaussDBConstant constant);

    default void visit(GaussDBExpression expression) {
        if (expression instanceof GaussDBConstant) {
            visit((GaussDBConstant) expression);
        } else {
            throw new AssertionError(expression);
        }
    }

}
