package sqlancer.gaussdb.ast;

import sqlancer.Randomly;
import sqlancer.common.visitor.UnaryOperation;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public final class GaussDBUnaryOperation implements UnaryOperation<GaussDBExpression>, GaussDBExpression {

    public enum Operator {
        NOT("NOT", OperatorKind.PREFIX), NEGATE("-", OperatorKind.PREFIX), IS_NULL("IS NULL",
                OperatorKind.POSTFIX), IS_NOT_NULL("IS NOT NULL", OperatorKind.POSTFIX), IS_TRUE("IS TRUE",
                        OperatorKind.POSTFIX), IS_FALSE("IS FALSE", OperatorKind.POSTFIX);

        private final String representation;
        private final OperatorKind kind;

        Operator(String representation, OperatorKind kind) {
            this.representation = representation;
            this.kind = kind;
        }

        public String getRepresentation() {
            return representation;
        }

        public OperatorKind getKind() {
            return kind;
        }

        public static Operator getRandomNullCheck() {
            return Randomly.fromOptions(IS_NULL, IS_NOT_NULL);
        }
    }

    private final GaussDBExpression expression;
    private final Operator operator;
    private final GaussDBDataType type;

    public GaussDBUnaryOperation(GaussDBExpression expression, Operator operator, GaussDBDataType type) {
        this.expression = expression;
        this.operator = operator;
        this.type = type;
    }

    @Override
    public GaussDBExpression getExpression() {
        return expression;
    }

    public Operator getOperator() {
        return operator;
    }

    @Override
    public String getOperatorRepresentation() {
        return operator.getRepresentation();
    }

    @Override
    public OperatorKind getOperatorKind() {
        return operator.getKind();
    }

    @Override
    public GaussDBDataType getExpressionType() {
        return type;
    }

}
