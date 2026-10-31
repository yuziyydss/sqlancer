package sqlancer.gaussdb.ast;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.common.ast.SelectBase;
import sqlancer.common.ast.newast.Select;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;

public final class GaussDBSelect extends SelectBase<GaussDBExpression>
        implements GaussDBExpression, Select<GaussDBJoin, GaussDBExpression, GaussDBTable, GaussDBColumn> {

    private boolean distinct;

    public boolean isDistinct() {
        return distinct;
    }

    public void setDistinct(boolean distinct) {
        this.distinct = distinct;
    }

    @Override
    public void setJoinClauses(List<GaussDBJoin> joinStatements) {
        setJoinList(joinStatements.stream().map(join -> (GaussDBExpression) join).collect(Collectors.toList()));
    }

    @Override
    public List<GaussDBJoin> getJoinClauses() {
        return getJoinList().stream().map(join -> (GaussDBJoin) join).collect(Collectors.toList());
    }

    @Override
    public String asString() {
        return sqlancer.gaussdb.GaussDBToStringVisitor.asString(this);
    }

}
