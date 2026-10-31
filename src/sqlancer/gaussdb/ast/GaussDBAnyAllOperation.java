package sqlancer.gaussdb.ast;

public final class GaussDBAnyAllOperation implements GaussDBExpression {

    public enum Quantifier {
        ANY, ALL;

        public static Quantifier getRandom() {
            return sqlancer.Randomly.fromOptions(values());
        }
    }

    private final GaussDBExpression left;
    private final GaussDBExpression right;
    private final GaussDBBinaryOperation.Operator operator;
    private final Quantifier quantifier;

    public GaussDBAnyAllOperation(GaussDBExpression left, GaussDBExpression right,
            GaussDBBinaryOperation.Operator operator, Quantifier quantifier) {
        this.left = left;
        this.right = right;
        this.operator = operator;
        this.quantifier = quantifier;
    }

    public GaussDBExpression getLeft() {
        return left;
    }

    public GaussDBExpression getRight() {
        return right;
    }

    public GaussDBBinaryOperation.Operator getOperator() {
        return operator;
    }

    public Quantifier getQuantifier() {
        return quantifier;
    }

}
