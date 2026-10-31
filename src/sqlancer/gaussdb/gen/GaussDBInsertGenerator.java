package sqlancer.gaussdb.gen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.schema.AbstractTableColumn;
import sqlancer.gaussdb.GaussDBErrors;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.GaussDBToStringVisitor;
import sqlancer.gaussdb.ast.GaussDBExpression;

public final class GaussDBInsertGenerator {

    private GaussDBInsertGenerator() {
    }

    public static SQLQueryAdapter insert(GaussDBGlobalState globalState) {
        GaussDBTable table = globalState.getSchema().getRandomTable(GaussDBTable::isInsertable);
        ExpectedErrors errors = new ExpectedErrors();
        errors.add("violates not-null constraint");
        errors.add("duplicate key value violates unique constraint");
        errors.add("invalid input syntax");
        GaussDBErrors.addCommonExpressionErrors(errors);

        List<GaussDBColumn> columns = table.getRandomNonEmptyColumnSubset();
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ");
        sb.append(table.getName());
        sb.append("(");
        sb.append(columns.stream().map(AbstractTableColumn::getName).collect(Collectors.joining(", ")));
        sb.append(") VALUES ");

        int n = Randomly.smallNumber() + 1;
        for (int i = 0; i < n; i++) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append("(");
            for (int j = 0; j < columns.size(); j++) {
                if (j != 0) {
                    sb.append(", ");
                }
                GaussDBExpression constant = GaussDBExpressionGenerator.generateConstant(globalState.getRandomly(),
                        columns.get(j).getType());
                sb.append(GaussDBToStringVisitor.asString(constant));
            }
            sb.append(")");
        }
        return new SQLQueryAdapter(sb.toString(), errors);
    }

}
