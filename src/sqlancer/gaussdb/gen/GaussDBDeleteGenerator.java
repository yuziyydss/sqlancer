package sqlancer.gaussdb.gen;

import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.gaussdb.GaussDBErrors;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;

public final class GaussDBDeleteGenerator {

    private GaussDBDeleteGenerator() {
    }

    public static SQLQueryAdapter delete(GaussDBGlobalState globalState) {
        GaussDBTable table = globalState.getSchema().getRandomTableOrBailout(t -> !t.isView());
        StringBuilder sb = new StringBuilder();
        sb.append("DELETE FROM ");
        sb.append(table.getName());
        sb.append(" WHERE ctid = (SELECT ctid FROM ");
        sb.append(table.getName());
        sb.append(" LIMIT 1)");

        ExpectedErrors errors = new ExpectedErrors();
        errors.add("violates foreign key constraint");
        GaussDBErrors.addCommonExpressionErrors(errors);
        return new SQLQueryAdapter(sb.toString(), errors);
    }

}
