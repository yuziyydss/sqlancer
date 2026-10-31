package sqlancer.gaussdb.gen;

import java.util.List;

import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.gaussdb.GaussDBErrors;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.GaussDBToStringVisitor;

public final class GaussDBUpdateGenerator {

    private GaussDBUpdateGenerator() {
    }

    public static SQLQueryAdapter update(GaussDBGlobalState globalState) {
        GaussDBTable table = globalState.getSchema().getRandomTableOrBailout(t -> !t.isView());
        List<GaussDBColumn> columns = table.getRandomNonEmptyColumnSubset();

        StringBuilder sb = new StringBuilder();
        sb.append("UPDATE ");
        sb.append(table.getName());
        sb.append(" SET ");
        for (int i = 0; i < columns.size(); i++) {
            if (i != 0) {
                sb.append(", ");
            }
            GaussDBColumn column = columns.get(i);
            sb.append(column.getName());
            sb.append(" = ");
            sb.append(GaussDBToStringVisitor.asString(
                    GaussDBExpressionGenerator.generateConstant(globalState.getRandomly(), column.getType())));
        }
        sb.append(" WHERE ctid = (SELECT ctid FROM ");
        sb.append(table.getName());
        sb.append(" LIMIT 1)");

        ExpectedErrors errors = new ExpectedErrors();
        errors.add("violates not-null constraint");
        errors.add("violates unique constraint");
        errors.add("out of range");
        errors.add("cannot cast");
        GaussDBErrors.addCommonExpressionErrors(errors);
        return new SQLQueryAdapter(sb.toString(), errors);
    }

}
