package sqlancer.gaussdb.gen;

import java.util.ArrayList;
import java.util.List;

import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.gaussdb.GaussDBErrors;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.GaussDBToStringVisitor;
import sqlancer.gaussdb.ast.GaussDBAlias;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBSelect;
import sqlancer.gaussdb.ast.GaussDBTableReference;

public final class GaussDBViewGenerator {

    private GaussDBViewGenerator() {
    }

    public static SQLQueryAdapter create(GaussDBGlobalState globalState) {
        GaussDBTable table = globalState.getSchema().getRandomTableOrBailout(t -> !t.isView());
        List<GaussDBColumn> columns = table.getRandomNonEmptyColumnSubset();

        GaussDBSelect select = new GaussDBSelect();
        List<GaussDBExpression> fetchColumns = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            fetchColumns.add(new GaussDBAlias(new GaussDBColumnReference(columns.get(i), "t"), "c" + i));
        }
        select.setFetchColumns(fetchColumns);
        select.setFromList(
                java.util.Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), "t")));

        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(globalState);
        generator.setColumns(table.getColumns());
        generator.setTableAlias("t");
        select.setWhereClause(generator.generateExpression(GaussDBDataType.BOOLEAN));

        String viewName = globalState.getSchema().getFreeViewName();
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE VIEW ");
        sb.append(viewName);
        sb.append("(");
        for (int i = 0; i < columns.size(); i++) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append("c");
            sb.append(i);
        }
        sb.append(") AS ");
        sb.append(GaussDBToStringVisitor.asString(select));

        ExpectedErrors errors = new ExpectedErrors();
        errors.add("already exists");
        errors.add("cannot change data type of view column");
        errors.add("column names are not unique");
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

}
