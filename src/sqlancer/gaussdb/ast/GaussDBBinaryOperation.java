package sqlancer.gaussdb.ast;

import sqlancer.Randomly;
import sqlancer.common.visitor.BinaryOperation;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public final class GaussDBBinaryOperation implements BinaryOperation<GaussDBExpression>, GaussDBExpression {

    public enum Operator {
        AND(" AND "), OR(" OR "), EQUALS(" = "), NOT_EQUALS(" <> "), LESS(" < "), LESS_EQUALS(" <= "), GREATER(
                " > "), GREATER_EQUALS(" >= "), PLUS(" + "), MINUS(" - "), MULTIPLY(" * "), DIVIDE(" / "), MODULO(
                        " % ");

        private final String representation;

        Operator(String representation) {
            this.representation = representation;
        }

        public String getRepresentation() {
            return representation;
        }

        public static Operator getRandomLogical() {
            return Randomly.fromOptions(AND, OR);
        }

        public static Operator getRandomComparison() {
            return Randomly.fromOptions(EQUALS, NOT_EQUALS, LESS, LESS_EQUALS, GREATER, GREATER_EQUALS);
        }

        public static Operator getRandomArithmetic() {
            return Randomly.fromOptions(PLUS, MINUS, MULTIPLY, DIVIDE, MODULO);
        }
    }

    private final GaussDBExpression left;
    private final GaussDBExpression right;
    private final Operator operator;
    private final GaussDBDataType type;

    public GaussDBBinaryOperation(GaussDBExpression left, GaussDBExpression right, Operator operator,
            GaussDBDataType type) {
        this.left = left;
        this.right = right;
        this.operator = operator;
        this.type = type;
    }

    @Override
    public GaussDBExpression getLeft() {
        return left;
    }

    @Override
    public GaussDBExpression getRight() {
        return right;
    }

    public Operator getOperator() {
        return operator;
    }

    @Override
    public String getOperatorRepresentation() {
        return operator.getRepresentation();
    }

    @Override
    public GaussDBDataType getExpressionType() {
        return type;
    }

}
