package sqlancer.gaussdb.gen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
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
        sb.append(columns.stream().map(GaussDBIndexGenerator::formatColumnSpec).collect(Collectors.joining(", ")));
        sb.append(")");

        ExpectedErrors errors = new ExpectedErrors();
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("cannot cast");
        errors.add("Global partitioned indexes and local indexes cannot be on the same column");
        errors.add("must contain all columns");
        errors.add("cannot create global");
        GaussDBErrors.addCommonExpressionErrors(errors);
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    private static String formatColumnSpec(GaussDBColumn column) {
        StringBuilder spec = new StringBuilder(column.getName());
        if (Randomly.getBoolean()) {
            spec.append(Randomly.fromOptions(" ASC", " DESC"));
        }
        if (Randomly.getBooleanWithSmallProbability()) {
            spec.append(Randomly.fromOptions(" NULLS FIRST", " NULLS LAST"));
        }
        return spec.toString();
    }

}
