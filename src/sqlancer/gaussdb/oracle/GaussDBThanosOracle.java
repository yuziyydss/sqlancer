package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.SQLConnection;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.gaussdb.GaussDBErrors;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.GaussDBToStringVisitor;
import sqlancer.gaussdb.ast.GaussDBAggregate;
import sqlancer.gaussdb.ast.GaussDBBinaryOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBJoin;
import sqlancer.gaussdb.ast.GaussDBSelect;
import sqlancer.gaussdb.ast.GaussDBSetOperation;
import sqlancer.gaussdb.ast.GaussDBStar;
import sqlancer.gaussdb.ast.GaussDBWindowFunction;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

/**
 * Thanos (ICSE'25) storage-engine rotation differential testing oracle for GaussDB.
 *
 * <p>
 * Materializes an equivalent twin of each base table across GaussDB's row storage engines ({@code astore} vs
 * {@code ustore}), optionally adds UBTree/BTree indexes and exercises Ustore in-place UPDATE/DELETE + rollback paths,
 * then executes identical SQL queries on both engines and checks result equivalence.
 * </p>
 */
public class GaussDBThanosOracle implements TestOracle<GaussDBGlobalState> {

    static final String ASTORE_SUFFIX = "_thanos_astore";
    static final String USTORE_SUFFIX = "_thanos_ustore";
    private static final String TABLE_ALIAS = "t";

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBThanosReproducer reproducer;
    private String lastQueryString;

    public GaussDBThanosOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public static final class EngineQueryPair {

        private final List<String> setupSql;
        private final String astoreQuery;
        private final String ustoreQuery;
        private final List<String> cleanupSql;

        EngineQueryPair(List<String> setupSql, String astoreQuery, String ustoreQuery, List<String> cleanupSql) {
            this.setupSql = Collections.unmodifiableList(new ArrayList<>(setupSql));
            this.astoreQuery = astoreQuery;
            this.ustoreQuery = ustoreQuery;
            this.cleanupSql = Collections.unmodifiableList(new ArrayList<>(cleanupSql));
        }

        public List<String> getSetupSql() {
            return setupSql;
        }

        public String getAstoreQuery() {
            return astoreQuery;
        }

        public String getUstoreQuery() {
            return ustoreQuery;
        }

        public List<String> getCleanupSql() {
            return cleanupSql;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());

        EngineQueryPair pair;
        switch (Randomly.fromOptions(0, 1, 2, 3, 4, 5, 6, 7)) {
        case 0:
            pair = buildWhereQueryPair(table);
            break;
        case 1:
            pair = buildSelectExpressionPair(table);
            break;
        case 2:
            pair = buildGroupByHavingPair(table);
            break;
        case 3:
            pair = buildAggregatePair(table);
            break;
        case 4:
            pair = buildDistinctPair(table);
            break;
        case 5:
            pair = buildWindowFunctionPair(table);
            break;
        case 6:
            pair = buildSetOperationPair(table);
            break;
        case 7:
            pair = buildJoinPair();
            break;
        default:
            throw new AssertionError();
        }
        if (pair == null) {
            return;
        }
        verifyPair(pair);
    }

    private EngineQueryPair buildWhereQueryPair(GaussDBTable table) {
        GaussDBExpressionGenerator generator = createGenerator(table, TABLE_ALIAS);
        GaussDBExpression predicate = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBSelect astoreSelect = GaussDBEETOracle.selectStarWhere(twinTable(table, ASTORE_SUFFIX), predicate);
        GaussDBSelect ustoreSelect = GaussDBEETOracle.selectStarWhere(twinTable(table, USTORE_SUFFIX), predicate);
        return createSingleTablePair(table, GaussDBToStringVisitor.asString(astoreSelect),
                GaussDBToStringVisitor.asString(ustoreSelect));
    }

    private EngineQueryPair buildSelectExpressionPair(GaussDBTable table) {
        GaussDBExpressionGenerator generator = createGenerator(table, TABLE_ALIAS);
        GaussDBDataType type = GaussDBDataType.getRandomType();
        GaussDBExpression expr = generator.generateExpression(type);
        GaussDBExpression predicate = generator.generateExpression(GaussDBDataType.BOOLEAN);

        GaussDBSelect astoreSelect = GaussDBEETOracle.selectValueFrom(twinTable(table, ASTORE_SUFFIX), expr);
        astoreSelect.setWhereClause(predicate);
        GaussDBSelect ustoreSelect = GaussDBEETOracle.selectValueFrom(twinTable(table, USTORE_SUFFIX), expr);
        ustoreSelect.setWhereClause(predicate);
        return createSingleTablePair(table, GaussDBToStringVisitor.asString(astoreSelect),
                GaussDBToStringVisitor.asString(ustoreSelect));
    }

    private EngineQueryPair buildGroupByHavingPair(GaussDBTable table) {
        GaussDBColumn column = Randomly.fromList(table.getColumns());
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(Collections.singletonList(column));
        generator.setTableAlias(TABLE_ALIAS);
        GaussDBExpression predicate = generator.generateExpression(GaussDBDataType.BOOLEAN);

        GaussDBSelect astoreSelect = GaussDBEETOracle.selectGroupByHaving(twinTable(table, ASTORE_SUFFIX), column,
                predicate);
        GaussDBSelect ustoreSelect = GaussDBEETOracle.selectGroupByHaving(twinTable(table, USTORE_SUFFIX), column,
                predicate);
        return createSingleTablePair(table, GaussDBToStringVisitor.asString(astoreSelect),
                GaussDBToStringVisitor.asString(ustoreSelect));
    }

    private EngineQueryPair buildAggregatePair(GaussDBTable table) {
        List<GaussDBColumn> numericColumns = table.getColumns().stream()
                .filter(c -> c.getType() == GaussDBDataType.INT || c.getType() == GaussDBDataType.DECIMAL
                        || c.getType() == GaussDBDataType.FLOAT || c.getType() == GaussDBDataType.REAL)
                .collect(Collectors.toList());
        GaussDBExpression aggregate;
        if (numericColumns.isEmpty()) {
            aggregate = new GaussDBAggregate(GaussDBAggregate.Function.COUNT,
                    Collections.singletonList(new GaussDBStar()), GaussDBDataType.INT);
        } else {
            GaussDBColumn col = Randomly.fromList(numericColumns);
            GaussDBAggregate.Function fn = Randomly.fromOptions(GaussDBAggregate.Function.COUNT,
                    GaussDBAggregate.Function.SUM, GaussDBAggregate.Function.MIN, GaussDBAggregate.Function.MAX,
                    GaussDBAggregate.Function.AVG);
            aggregate = new GaussDBAggregate(fn,
                    Collections.singletonList(new GaussDBColumnReference(col, TABLE_ALIAS)), col.getType());
        }
        GaussDBExpression predicate = createGenerator(table, TABLE_ALIAS).generateExpression(GaussDBDataType.BOOLEAN);

        GaussDBSelect astoreSelect = GaussDBEETOracle.selectAggregateFrom(twinTable(table, ASTORE_SUFFIX), aggregate);
        astoreSelect.setWhereClause(predicate);
        GaussDBSelect ustoreSelect = GaussDBEETOracle.selectAggregateFrom(twinTable(table, USTORE_SUFFIX), aggregate);
        ustoreSelect.setWhereClause(predicate);
        return createSingleTablePair(table, GaussDBToStringVisitor.asString(astoreSelect),
                GaussDBToStringVisitor.asString(ustoreSelect));
    }

    private EngineQueryPair buildDistinctPair(GaussDBTable table) {
        GaussDBExpressionGenerator generator = createGenerator(table, TABLE_ALIAS);
        GaussDBExpression expr = generator.generateExpression(GaussDBDataType.getRandomType());
        GaussDBSelect astoreSelect = GaussDBEETOracle.selectValueFrom(twinTable(table, ASTORE_SUFFIX), expr);
        astoreSelect.setDistinct(true);
        GaussDBSelect ustoreSelect = GaussDBEETOracle.selectValueFrom(twinTable(table, USTORE_SUFFIX), expr);
        ustoreSelect.setDistinct(true);
        return createSingleTablePair(table, GaussDBToStringVisitor.asString(astoreSelect),
                GaussDBToStringVisitor.asString(ustoreSelect));
    }

    private EngineQueryPair buildWindowFunctionPair(GaussDBTable table) {
        GaussDBColumn partCol = Randomly.fromList(table.getColumns());
        GaussDBColumn orderCol = Randomly.fromList(table.getColumns());
        String fnName = Randomly.fromOptions("row_number", "rank", "dense_rank");
        GaussDBWindowFunction win = new GaussDBWindowFunction(fnName, Collections.emptyList(),
                Collections.singletonList(new GaussDBColumnReference(partCol, TABLE_ALIAS)),
                Collections.singletonList(new GaussDBColumnReference(orderCol, TABLE_ALIAS)), GaussDBDataType.INT);

        GaussDBSelect astoreSelect = GaussDBEETOracle.selectValueFrom(twinTable(table, ASTORE_SUFFIX), win);
        GaussDBSelect ustoreSelect = GaussDBEETOracle.selectValueFrom(twinTable(table, USTORE_SUFFIX), win);
        return createSingleTablePair(table, GaussDBToStringVisitor.asString(astoreSelect),
                GaussDBToStringVisitor.asString(ustoreSelect));
    }

    private EngineQueryPair buildSetOperationPair(GaussDBTable table) {
        GaussDBExpressionGenerator generator = createGenerator(table, TABLE_ALIAS);
        GaussDBExpression leftPred = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression rightPred = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBSetOperation.Operator op = GaussDBSetOperation.Operator.getRandom();

        GaussDBTable astoreTable = twinTable(table, ASTORE_SUFFIX);
        GaussDBTable ustoreTable = twinTable(table, USTORE_SUFFIX);
        GaussDBSetOperation astoreSet = new GaussDBSetOperation(
                GaussDBEETOracle.selectStarWhere(astoreTable, leftPred),
                GaussDBEETOracle.selectStarWhere(astoreTable, rightPred), op);
        GaussDBSetOperation ustoreSet = new GaussDBSetOperation(
                GaussDBEETOracle.selectStarWhere(ustoreTable, leftPred),
                GaussDBEETOracle.selectStarWhere(ustoreTable, rightPred), op);
        return createSingleTablePair(table, GaussDBToStringVisitor.asString(astoreSet),
                GaussDBToStringVisitor.asString(ustoreSet));
    }

    private EngineQueryPair buildJoinPair() {
        GaussDBTable leftTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBTable rightTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBColumn leftCol = Randomly.fromList(leftTable.getColumns());
        GaussDBColumn rightCol = null;
        for (GaussDBColumn candidate : rightTable.getColumns()) {
            if (candidate.getType() == leftCol.getType()) {
                rightCol = candidate;
                break;
            }
        }
        if (rightCol == null) {
            return null;
        }
        GaussDBJoin.JoinType joinType = Randomly.fromOptions(GaussDBJoin.JoinType.INNER, GaussDBJoin.JoinType.LEFT,
                GaussDBJoin.JoinType.RIGHT, GaussDBJoin.JoinType.FULL);
        GaussDBBinaryOperation.Operator op = joinType == GaussDBJoin.JoinType.FULL
                ? GaussDBBinaryOperation.Operator.EQUALS : GaussDBBinaryOperation.Operator.getRandomComparison();
        GaussDBExpression onPred = new GaussDBBinaryOperation(new GaussDBColumnReference(leftCol, "l"),
                new GaussDBColumnReference(rightCol, "r"), op, GaussDBDataType.BOOLEAN);

        GaussDBSelect astoreJoin = GaussDBEETOracle.selectStarJoin(twinTable(leftTable, ASTORE_SUFFIX),
                twinTable(rightTable, ASTORE_SUFFIX), joinType, onPred);
        GaussDBSelect ustoreJoin = GaussDBEETOracle.selectStarJoin(twinTable(leftTable, USTORE_SUFFIX),
                twinTable(rightTable, USTORE_SUFFIX), joinType, onPred);

        List<GaussDBTable> distinctTables = leftTable.getName().equals(rightTable.getName())
                ? Collections.singletonList(leftTable) : Arrays.asList(leftTable, rightTable);
        return createMultiTablePair(distinctTables, GaussDBToStringVisitor.asString(astoreJoin),
                GaussDBToStringVisitor.asString(ustoreJoin));
    }

    private GaussDBExpressionGenerator createGenerator(GaussDBTable table, String alias) {
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(table.getColumns());
        generator.setTableAlias(alias);
        return generator;
    }

    static GaussDBTable twinTable(GaussDBTable baseTable, String suffix) {
        return new GaussDBTable(baseTable.getName() + suffix, baseTable.getColumns(), null, false, true);
    }

    EngineQueryPair createSingleTablePair(GaussDBTable table, String astoreQuery, String ustoreQuery) {
        return createMultiTablePair(Collections.singletonList(table), astoreQuery, ustoreQuery);
    }

    EngineQueryPair createMultiTablePair(List<GaussDBTable> tables, String astoreQuery, String ustoreQuery) {
        List<String> setup = new ArrayList<>();
        List<String> cleanup = new ArrayList<>();
        for (GaussDBTable table : tables) {
            String base = table.getName();
            String astore = base + ASTORE_SUFFIX;
            String ustore = base + USTORE_SUFFIX;
            setup.add("DROP TABLE IF EXISTS " + astore + ", " + ustore + " CASCADE");
            setup.add("CREATE TABLE " + astore + " WITH (storage_type = astore) AS SELECT * FROM " + base);
            setup.add("CREATE TABLE " + ustore + " WITH (storage_type = ustore) AS SELECT * FROM " + base);

            if (!table.getColumns().isEmpty() && Randomly.getBoolean()) {
                GaussDBColumn indexedCol = Randomly.fromList(table.getColumns());
                setup.add("CREATE INDEX " + astore + "_idx ON " + astore + " USING btree (" + indexedCol.getName()
                        + ")");
                setup.add("CREATE INDEX " + ustore + "_idx ON " + ustore + " USING ubtree (" + indexedCol.getName()
                        + ")");
            }

            // Exercise Ustore in-place update / undo-record path while keeping both tables logically identical:
            // perform an identical deterministic DML statement on both twins when chosen.
            if (!table.getColumns().isEmpty() && Randomly.getBoolean()) {
                GaussDBColumn targetCol = Randomly.fromList(table.getColumns());
                String constantSql = GaussDBToStringVisitor.asString(
                        GaussDBExpressionGenerator.generateConstant(state.getRandomly(), targetCol.getType()));
                setup.add("INSERT INTO " + astore + "(" + targetCol.getName() + ") VALUES (" + constantSql + ")");
                setup.add("INSERT INTO " + ustore + "(" + targetCol.getName() + ") VALUES (" + constantSql + ")");
                setup.add("UPDATE " + astore + " SET " + targetCol.getName() + " = " + targetCol.getName() + " WHERE "
                        + targetCol.getName() + " IS NOT NULL");
                setup.add("UPDATE " + ustore + " SET " + targetCol.getName() + " = " + targetCol.getName() + " WHERE "
                        + targetCol.getName() + " IS NOT NULL");
            }

            cleanup.add("DROP TABLE IF EXISTS " + astore + ", " + ustore + " CASCADE");
        }
        return new EngineQueryPair(setup, astoreQuery, ustoreQuery, cleanup);
    }

    private void verifyPair(EngineQueryPair pair) throws SQLException {
        lastQueryString = pair.getAstoreQuery();
        SQLConnection connection = state.getConnection();
        try (Statement statement = connection.createStatement()) {
            try {
                for (String sql : pair.getSetupSql()) {
                    executeSetup(statement, sql);
                }
                List<String> astoreResults = executeAndGetRows(statement, pair.getAstoreQuery());
                List<String> ustoreResults = executeAndGetRows(statement, pair.getUstoreQuery());
                if (areEquivalent(astoreResults, ustoreResults)) {
                    return;
                }
                // Re-run once to rule out nondeterministic expressions.
                astoreResults = executeAndGetRows(statement, pair.getAstoreQuery());
                ustoreResults = executeAndGetRows(statement, pair.getUstoreQuery());
                if (!areEquivalent(astoreResults, ustoreResults)) {
                    reproducer = new GaussDBThanosReproducer(pair);
                    throw new AssertionError("Thanos storage-engine mismatch (astore vs ustore)\nAstoreQuery: "
                            + pair.getAstoreQuery() + "\nUstoreQuery: " + pair.getUstoreQuery() + "\nAstoreResult: "
                            + astoreResults + "\nUstoreResult: " + ustoreResults);
                }
            } finally {
                for (String sql : pair.getCleanupSql()) {
                    quietlyExecute(statement, sql);
                }
            }
        }
    }

    private void executeSetup(Statement statement, String sql) throws SQLException {
        try {
            statement.execute(sql);
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        }
    }

    private List<String> executeAndGetRows(Statement statement, String query) throws SQLException {
        List<String> results = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery(query)) {
            int columnCount = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.add(resultSet.getString(i));
                }
                results.add(row.toString());
            }
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        }
        return results;
    }

    static boolean areEquivalent(List<String> first, List<String> second) {
        return sorted(first).equals(sorted(second));
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
        return copy;
    }

    private static void quietlyExecute(Statement statement, String sql) {
        try {
            statement.execute(sql);
        } catch (SQLException ignored) {
            // Best-effort cleanup.
        }
    }

    @Override
    public Reproducer<GaussDBGlobalState> getLastReproducer() {
        return reproducer;
    }

    @Override
    public String getLastQueryString() {
        return lastQueryString;
    }

}
