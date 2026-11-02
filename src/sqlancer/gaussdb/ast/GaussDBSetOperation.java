package sqlancer.gaussdb.ast;

import sqlancer.Randomly;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public final class GaussDBSetOperation implements GaussDBExpression {

    public enum Operator {
        UNION("UNION"), UNION_ALL("UNION ALL"), INTERSECT("INTERSECT"), EXCEPT("EXCEPT");

        private final String representation;

        Operator(String representation) {
            this.representation = representation;
        }

        public String getRepresentation() {
            return representation;
        }

        public static Operator getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    private final GaussDBSelect left;
    private final GaussDBSelect right;
    private final Operator operator;

    public GaussDBSetOperation(GaussDBSelect left, GaussDBSelect right, Operator operator) {
        this.left = left;
        this.right = right;
        this.operator = operator;
    }

    public GaussDBSelect getLeft() {
        return left;
    }

    public GaussDBSelect getRight() {
        return right;
    }

    public Operator getOperator() {
        return operator;
    }

    @Override
    public GaussDBDataType getExpressionType() {
        return null;
    }

}
