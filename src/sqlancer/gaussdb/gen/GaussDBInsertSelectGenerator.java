package sqlancer.gaussdb.gen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.gaussdb.GaussDBErrors;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;

public final class GaussDBInsertSelectGenerator {

    private GaussDBInsertSelectGenerator() {
    }

    public static SQLQueryAdapter insertSelect(GaussDBGlobalState globalState) {
        GaussDBTable table = globalState.getSchema().getRandomTableOrBailout(t -> !t.isView());
        List<GaussDBColumn> columns = table.getRandomNonEmptyColumnSubset();
        String columnNames = columns.stream().map(GaussDBColumn::getName).collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ");
        sb.append(table.getName());
        sb.append("(");
        sb.append(columnNames);
        sb.append(") SELECT ");
        sb.append(columnNames);
        sb.append(" FROM ");
        sb.append(table.getName());
        sb.append(" LIMIT 1");

        ExpectedErrors errors = new ExpectedErrors();
        errors.add("violates not-null constraint");
        errors.add("violates unique constraint");
        errors.add("out of range");
        errors.add("cannot cast");
        GaussDBErrors.addCommonExpressionErrors(errors);
        return new SQLQueryAdapter(sb.toString(), errors);
    }

}
