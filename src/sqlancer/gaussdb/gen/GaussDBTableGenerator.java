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
        if (Randomly.getBoolean()) {
            sb.append(" WITH (storage_type = ");
            sb.append(Randomly.fromOptions("ustore", "ustore", "astore"));
            sb.append(")");
        }
        appendOptionalPartitionClause();
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    private void appendOptionalPartitionClause() {
        if (!Randomly.getBoolean()) {
            return;
        }
        List<GaussDBColumn> intColumns = new ArrayList<>();
        for (GaussDBColumn column : columnsToBeAdded) {
            if (column.getType() == GaussDBDataType.INT) {
                intColumns.add(column);
            }
        }
        if (intColumns.isEmpty()) {
            return;
        }
        GaussDBColumn partCol = Randomly.fromList(intColumns);
        if (Randomly.getBoolean()) {
            sb.append(" PARTITION BY RANGE (").append(partCol.getName()).append(") (")
                    .append("PARTITION p0 VALUES LESS THAN (-100), ")
                    .append("PARTITION p1 VALUES LESS THAN (200), ")
                    .append("PARTITION pmax VALUES LESS THAN (MAXVALUE)) ENABLE ROW MOVEMENT");
        } else {
            sb.append(" PARTITION BY HASH (").append(partCol.getName()).append(") (")
                    .append("PARTITION p0, PARTITION p1, PARTITION p2) ENABLE ROW MOVEMENT");
        }
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
