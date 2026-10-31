package sqlancer.gaussdb;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.common.DBMSCommon;
import sqlancer.common.schema.AbstractRelationalTable;
import sqlancer.common.schema.AbstractRowValue;
import sqlancer.common.schema.AbstractSchema;
import sqlancer.common.schema.AbstractTableColumn;
import sqlancer.common.schema.AbstractTables;
import sqlancer.common.schema.TableIndex;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.ast.GaussDBConstant;

public class GaussDBSchema extends AbstractSchema<GaussDBGlobalState, GaussDBTable> {

    private final String databaseName;

    public GaussDBSchema(List<GaussDBTable> databaseTables, String databaseName) {
        super(databaseTables);
        this.databaseName = databaseName;
    }

    public static GaussDBSchema fromConnection(SQLConnection con, String databaseName) throws SQLException {
        List<GaussDBTable> databaseTables = new ArrayList<>();
        try (Statement s = con.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = current_schema() AND table_type IN ('BASE TABLE', 'VIEW') ORDER BY table_name;")) {
                while (rs.next()) {
                    String tableName = rs.getString("table_name");
                    boolean isView = tableName.startsWith("v");
                    List<GaussDBColumn> columns = getTableColumns(con, tableName);
                    List<GaussDBIndex> indexes = getIndexes(con, tableName);
                    GaussDBTable table = new GaussDBTable(tableName, columns, indexes, isView, !isView);
                    for (GaussDBColumn c : columns) {
                        c.setTable(table);
                    }
                    databaseTables.add(table);
                }
            }
        }
        return new GaussDBSchema(databaseTables, databaseName);
    }

    private static List<GaussDBColumn> getTableColumns(SQLConnection con, String tableName) throws SQLException {
        List<GaussDBColumn> columns = new ArrayList<>();
        try (Statement s = con.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT column_name, data_type FROM information_schema.columns "
                    + "WHERE table_schema = current_schema() AND table_name = '" + tableName
                    + "' ORDER BY ordinal_position;")) {
                while (rs.next()) {
                    columns.add(
                            new GaussDBColumn(rs.getString("column_name"), getColumnType(rs.getString("data_type"))));
                }
            }
        }
        return columns;
    }

    private static List<GaussDBIndex> getIndexes(SQLConnection con, String tableName) throws SQLException {
        List<GaussDBIndex> indexes = new ArrayList<>();
        try (Statement s = con.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() "
                    + "AND tablename = '" + tableName + "' ORDER BY indexname;")) {
                while (rs.next()) {
                    String indexName = rs.getString("indexname");
                    if (DBMSCommon.matchesIndexName(indexName)) {
                        indexes.add(GaussDBIndex.create(indexName));
                    }
                }
            }
        }
        return indexes;
    }

    private static GaussDBDataType getColumnType(String dataType) {
        switch (dataType) {
        case "smallint":
        case "integer":
        case "bigint":
            return GaussDBDataType.INT;
        case "boolean":
            return GaussDBDataType.BOOLEAN;
        case "character varying":
        case "character":
        case "text":
        case "name":
            return GaussDBDataType.TEXT;
        case "numeric":
            return GaussDBDataType.DECIMAL;
        case "real":
            return GaussDBDataType.REAL;
        case "double precision":
            return GaussDBDataType.FLOAT;
        case "date":
        case "datea":
            return GaussDBDataType.DATE;
        case "timestamp without time zone":
            return GaussDBDataType.TIMESTAMP;
        case "timestamp with time zone":
            return GaussDBDataType.TIMESTAMPTZ;
        case "time without time zone":
            return GaussDBDataType.TIME;
        case "interval":
            return GaussDBDataType.INTERVAL;
        default:
            throw new IgnoreMeException();
        }
    }

    public GaussDBTables getRandomTableNonEmptyTables() {
        return new GaussDBTables(Randomly.nonEmptySubset(getDatabaseTables()));
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public enum GaussDBDataType {
        INT, BOOLEAN, TEXT, DECIMAL, FLOAT, REAL, DATE, TIMESTAMP, TIMESTAMPTZ, TIME, INTERVAL;

        public static GaussDBDataType getRandomType() {
            return Randomly.fromList(new ArrayList<>(Arrays.asList(values())));
        }
    }

    public static class GaussDBColumn extends AbstractTableColumn<GaussDBTable, GaussDBDataType> {

        public GaussDBColumn(String name, GaussDBDataType columnType) {
            super(name, null, columnType);
        }

        public static GaussDBColumn createDummy(String name) {
            return new GaussDBColumn(name, GaussDBDataType.INT);
        }

    }

    public static class GaussDBTables extends AbstractTables<GaussDBTable, GaussDBColumn> {

        public GaussDBTables(List<GaussDBTable> tables) {
            super(tables);
        }

        public GaussDBRowValue getRandomRowValue(SQLConnection con) throws SQLException {
            String randomRow = String.format("SELECT %s FROM %s ORDER BY RANDOM() LIMIT 1", columnNamesAsString(
                    c -> c.getTable().getName() + "." + c.getName() + " AS " + c.getTable().getName() + c.getName()),
                    tableNamesAsString());
            Map<GaussDBColumn, GaussDBConstant> values = new HashMap<>();
            try (Statement s = con.createStatement()) {
                ResultSet randomRowValues = s.executeQuery(randomRow);
                if (!randomRowValues.next()) {
                    throw new AssertionError("could not find random row! " + randomRow + "\n");
                }
                for (int i = 0; i < getColumns().size(); i++) {
                    GaussDBColumn column = getColumns().get(i);
                    int columnIndex = randomRowValues.findColumn(column.getTable().getName() + column.getName());
                    assert columnIndex == i + 1;
                    GaussDBConstant constant;
                    if (randomRowValues.getString(columnIndex) == null) {
                        constant = GaussDBConstant.createNullConstant();
                    } else {
                        switch (column.getType()) {
                        case INT:
                            constant = GaussDBConstant.createIntConstant(randomRowValues.getLong(columnIndex));
                            break;
                        case BOOLEAN:
                            constant = GaussDBConstant.createBooleanConstant(randomRowValues.getBoolean(columnIndex));
                            break;
                        case TEXT:
                            constant = GaussDBConstant.createTextConstant(randomRowValues.getString(columnIndex));
                            break;
                        case DATE:
                            constant = GaussDBConstant.createDateConstant(randomRowValues.getString(columnIndex));
                            break;
                        case TIMESTAMP:
                            constant = GaussDBConstant.createTimestampConstant(randomRowValues.getString(columnIndex));
                            break;
                        case TIMESTAMPTZ:
                            constant = GaussDBConstant.createTimestamptzConstant(randomRowValues.getString(columnIndex));
                            break;
                        case TIME:
                            constant = GaussDBConstant.createTimeConstant(randomRowValues.getString(columnIndex));
                            break;
                        case INTERVAL:
                            constant = GaussDBConstant.createIntervalConstant(randomRowValues.getString(columnIndex));
                            break;
                        default:
                            throw new IgnoreMeException();
                        }
                    }
                    values.put(column, constant);
                }
                assert !randomRowValues.next();
                return new GaussDBRowValue(this, values);
            }
        }

    }

    public static class GaussDBRowValue extends AbstractRowValue<GaussDBTables, GaussDBColumn, GaussDBConstant> {

        protected GaussDBRowValue(GaussDBTables tables, Map<GaussDBColumn, GaussDBConstant> values) {
            super(tables, values);
        }

    }

    public static class GaussDBTable extends AbstractRelationalTable<GaussDBColumn, GaussDBIndex, GaussDBGlobalState> {

        private final boolean isInsertable;

        public GaussDBTable(String tableName, List<GaussDBColumn> columns, List<GaussDBIndex> indexes, boolean isView,
                boolean isInsertable) {
            super(tableName, columns, indexes, isView);
            this.isInsertable = isInsertable;
        }

        public boolean isInsertable() {
            return isInsertable;
        }

    }

    public static final class GaussDBIndex extends TableIndex {

        private GaussDBIndex(String indexName) {
            super(indexName);
        }

        public static GaussDBIndex create(String indexName) {
            return new GaussDBIndex(indexName);
        }

    }

}
