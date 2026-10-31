package sqlancer.gaussdb.ast;

import java.util.List;

import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public final class GaussDBFunction implements GaussDBExpression {

    private final String functionName;
    private final List<GaussDBExpression> arguments;
    private final GaussDBDataType returnType;

    public GaussDBFunction(String functionName, List<GaussDBExpression> arguments, GaussDBDataType returnType) {
        this.functionName = functionName;
        this.arguments = arguments;
        this.returnType = returnType;
    }

    public String getFunctionName() {
        return functionName;
    }

    public List<GaussDBExpression> getArguments() {
        return arguments;
    }

    @Override
    public GaussDBDataType getExpressionType() {
        return returnType;
    }

}
