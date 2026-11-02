package sqlancer.gaussdb.ast;

import java.util.List;

import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public final class GaussDBWindowFunction implements GaussDBExpression {

    private final String functionName;
    private final List<GaussDBExpression> arguments;
    private final List<GaussDBExpression> partitionBy;
    private final List<GaussDBExpression> orderBy;
    private final GaussDBDataType returnType;
    private final GaussDBWindowFrame frame;
    private final GaussDBExpression filterClause;

    public GaussDBWindowFunction(String functionName, List<GaussDBExpression> arguments,
            List<GaussDBExpression> partitionBy, List<GaussDBExpression> orderBy, GaussDBDataType returnType) {
        this(functionName, arguments, partitionBy, orderBy, returnType, null, null);
    }

    public GaussDBWindowFunction(String functionName, List<GaussDBExpression> arguments,
            List<GaussDBExpression> partitionBy, List<GaussDBExpression> orderBy, GaussDBDataType returnType,
            GaussDBWindowFrame frame) {
        this(functionName, arguments, partitionBy, orderBy, returnType, frame, null);
    }

    public GaussDBWindowFunction(String functionName, List<GaussDBExpression> arguments,
            List<GaussDBExpression> partitionBy, List<GaussDBExpression> orderBy, GaussDBDataType returnType,
            GaussDBWindowFrame frame, GaussDBExpression filterClause) {
        this.functionName = functionName;
        this.arguments = arguments;
        this.partitionBy = partitionBy;
        this.orderBy = orderBy;
        this.returnType = returnType;
        this.frame = frame;
        this.filterClause = filterClause;
    }

    public String getFunctionName() {
        return functionName;
    }

    public List<GaussDBExpression> getArguments() {
        return arguments;
    }

    public List<GaussDBExpression> getPartitionBy() {
        return partitionBy;
    }

    public List<GaussDBExpression> getOrderBy() {
        return orderBy;
    }

    public GaussDBWindowFrame getFrame() {
        return frame;
    }

    public GaussDBExpression getFilterClause() {
        return filterClause;
    }

    @Override
    public GaussDBDataType getExpressionType() {
        return returnType;
    }

}
