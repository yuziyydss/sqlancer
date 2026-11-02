package sqlancer.gaussdb.ast;

import java.util.List;

import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public final class GaussDBWithClauseGroup implements GaussDBExpression {

    private final List<GaussDBWithClause> clauses;

    public GaussDBWithClauseGroup(List<GaussDBWithClause> clauses) {
        this.clauses = clauses;
    }

    public List<GaussDBWithClause> getClauses() {
        return clauses;
    }

    @Override
    public GaussDBDataType getExpressionType() {
        return null;
    }
}
