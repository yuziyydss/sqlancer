package sqlancer.gaussdb.gen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.gaussdb.GaussDBErrors;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;

public final class GaussDBIndexGenerator {

    private GaussDBIndexGenerator() {
    }

    public static SQLQueryAdapter generate(GaussDBGlobalState globalState) {
        GaussDBTable table = globalState.getSchema().getRandomTableOrBailout(t -> !t.isView());
        List<GaussDBColumn> columns = table.getRandomNonEmptyColumnSubset();
        String indexName = globalState.getSchema().getFreeIndexName();

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE INDEX ");
        sb.append(indexName);
        sb.append(" ON ");
        sb.append(table.getName());
        sb.append(" USING BTREE (");
        sb.append(columns.stream().map(GaussDBColumn::getName).collect(Collectors.joining(", ")));
        sb.append(")");

        ExpectedErrors errors = new ExpectedErrors();
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("cannot cast");
        GaussDBErrors.addCommonExpressionErrors(errors);
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

}
