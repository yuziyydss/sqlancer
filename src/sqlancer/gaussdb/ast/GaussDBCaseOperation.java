package sqlancer.gaussdb.ast;

import java.util.List;

import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public final class GaussDBCaseOperation implements GaussDBExpression {

    private final List<GaussDBExpression> conditions;
    private final List<GaussDBExpression> results;
    private final GaussDBExpression elseExpression;
    private final GaussDBDataType type;

    public GaussDBCaseOperation(List<GaussDBExpression> conditions, List<GaussDBExpression> results,
            GaussDBExpression elseExpression, GaussDBDataType type) {
        this.conditions = conditions;
        this.results = results;
        this.elseExpression = elseExpression;
        this.type = type;
    }

    public List<GaussDBExpression> getConditions() {
        return conditions;
    }

    public List<GaussDBExpression> getResults() {
        return results;
    }

    public GaussDBExpression getElseExpression() {
        return elseExpression;
    }

    @Override
    public GaussDBDataType getExpressionType() {
        return type;
    }

}
