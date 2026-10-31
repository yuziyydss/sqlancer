package sqlancer.gaussdb.ast;

import java.util.List;

import sqlancer.Randomly;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public final class GaussDBAggregate implements GaussDBExpression {

    public enum Function {
        COUNT, SUM, MIN, MAX, AVG;

        public static Function getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    private final Function function;
    private final List<GaussDBExpression> arguments;
    private final GaussDBDataType type;

    public GaussDBAggregate(Function function, List<GaussDBExpression> arguments, GaussDBDataType type) {
        this.function = function;
        this.arguments = arguments;
        this.type = type;
    }

    public Function getFunction() {
        return function;
    }

    public List<GaussDBExpression> getArguments() {
        return arguments;
    }

    @Override
    public GaussDBDataType getExpressionType() {
        return type;
    }

}
