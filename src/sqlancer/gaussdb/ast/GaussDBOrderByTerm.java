package sqlancer.gaussdb.ast;

import sqlancer.Randomly;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public class GaussDBOrderByTerm implements GaussDBExpression {

    public enum Order {
        ASC("ASC"),
        DESC("DESC");

        private final String representation;

        Order(String representation) {
            this.representation = representation;
        }

        public String getRepresentation() {
            return representation;
        }

        public static Order getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    public enum NullsOrder {
        NONE(""),
        NULLS_FIRST("NULLS FIRST"),
        NULLS_LAST("NULLS LAST");

        private final String representation;

        NullsOrder(String representation) {
            this.representation = representation;
        }

        public String getRepresentation() {
            return representation;
        }

        public static NullsOrder getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    private final GaussDBExpression expression;
    private final Order order;
    private final NullsOrder nullsOrder;

    public GaussDBOrderByTerm(GaussDBExpression expression, Order order, NullsOrder nullsOrder) {
        this.expression = expression;
        this.order = order;
        this.nullsOrder = nullsOrder;
    }

    public GaussDBExpression getExpression() {
        return expression;
    }

    public Order getOrder() {
        return order;
    }

    public NullsOrder getNullsOrder() {
        return nullsOrder;
    }

    @Override
    public GaussDBDataType getExpressionType() {
        return expression.getExpressionType();
    }

}
