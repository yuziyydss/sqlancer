package sqlancer.gaussdb.gen;

import java.util.ArrayList;
import java.util.List;

import sqlancer.Randomly;
import sqlancer.common.DBMSCommon;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.gaussdb.GaussDBErrors;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;

public class GaussDBTableGenerator {

    private final ExpectedErrors errors = new ExpectedErrors();
    private final StringBuilder sb = new StringBuilder();
    private final List<GaussDBColumn> columnsToBeAdded = new ArrayList<>();
    private final GaussDBTable table;

    public GaussDBTableGenerator(String tableName) {
        this.table = new GaussDBTable(tableName, columnsToBeAdded, null, false, true);
        errors.add("already exists");
        errors.add("invalid input syntax");
        GaussDBErrors.addCommonExpressionErrors(errors);
    }

    public static SQLQueryAdapter generate(String tableName, GaussDBGlobalState globalState) {
        return new GaussDBTableGenerator(tableName).generate();
    }

    private SQLQueryAdapter generate() {
        sb.append("CREATE TABLE IF NOT EXISTS ").append(table.getName()).append("(");
        for (int i = 0; i < Randomly.smallNumber() + 1; i++) {
            if (i != 0) {
                sb.append(", ");
            }
            createColumn(DBMSCommon.createColumnName(i));
        }
        sb.append(")");
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    private void createColumn(String name) {
        sb.append(name).append(" ");
        GaussDBDataType type = GaussDBDataType.getRandomType();
        appendDataType(type);
        GaussDBColumn c = new GaussDBColumn(name, type);
        c.setTable(table);
        columnsToBeAdded.add(c);
        sb.append(" ");
        sb.append("NULL");
    }

    private void appendDataType(GaussDBDataType type) {
        switch (type) {
        case INT:
            sb.append(Randomly.fromOptions("SMALLINT", "INT", "BIGINT"));
            break;
        case BOOLEAN:
            sb.append("BOOLEAN");
            break;
        case TEXT:
            sb.append("TEXT");
            break;
        case DECIMAL:
            sb.append("NUMERIC");
            break;
        case FLOAT:
            sb.append("DOUBLE PRECISION");
            break;
        case REAL:
            sb.append("REAL");
            break;
        case DATE:
            sb.append("DATE");
            break;
        case TIMESTAMP:
            sb.append("TIMESTAMP");
            break;
        case TIMESTAMPTZ:
            sb.append("TIMESTAMPTZ");
            break;
        case TIME:
            sb.append("TIME");
            break;
        case INTERVAL:
            sb.append("INTERVAL");
            break;
        default:
            throw new AssertionError(type);
        }
    }

}
