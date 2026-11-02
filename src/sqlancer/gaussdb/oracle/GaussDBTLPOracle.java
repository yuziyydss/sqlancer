package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
import sqlancer.gaussdb.ast.GaussDBAlias;
import sqlancer.gaussdb.ast.GaussDBBinaryOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBJoin;
import sqlancer.gaussdb.ast.GaussDBSelect;
import sqlancer.gaussdb.ast.GaussDBSetOperation;
import sqlancer.gaussdb.ast.GaussDBStar;
import sqlancer.gaussdb.ast.GaussDBTableReference;
import sqlancer.gaussdb.ast.GaussDBUnaryOperation;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

/**
 * Ternary Logic Partitioning (TLP, OOPSLA'20) oracle for GaussDB.
 *
 * <p>
 * Covers TLP-WHERE, TLP-DISTINCT, TLP-GROUP-BY, TLP-HAVING, TLP-AGGREGATE (MIN/MAX/SUM/COUNT), and TLP-JOIN.
 * </p>
 */
public class GaussDBTLPOracle implements TestOracle<GaussDBGlobalState> {

    private static final String TABLE_ALIAS = "t";

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBTLPReproducer reproducer;
    private String lastQueryString;

    public GaussDBTLPOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public static final class TLPQueryPair {

        private final String originalQuery;
        private final String partitionedQuery;

        TLPQueryPair(String originalQuery, String partitionedQuery) {
            this.originalQuery = originalQuery;
            this.partitionedQuery = partitionedQuery;
        }

        public String getOriginalQuery() {
            return originalQuery;
        }

        public String getPartitionedQuery() {
            return partitionedQuery;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(table.getColumns());
        generator.setTableAlias(TABLE_ALIAS);

        boolean bypassBug = state.getDbmsSpecificOptions() == null
                || state.getDbmsSpecificOptions().bypassBooleanTestRangeBug;
        TLPQueryPair pair;
        switch (Randomly.fromOptions(0, 1, 2, 3, 4, 5)) {
        case 0:
            pair = createWherePair(table, generator.generateExpression(GaussDBDataType.BOOLEAN), bypassBug);
            break;
        case 1:
            pair = createDistinctPair(table, generator.generateExpression(GaussDBDataType.BOOLEAN), bypassBug);
            break;
        case 2:
            pair = createGroupByPair(table, Randomly.fromList(table.getColumns()),
                    generator.generateExpression(GaussDBDataType.BOOLEAN), bypassBug);
            break;
        case 3:
            pair = buildHavingPair(table, bypassBug);
            break;
        case 4:
            pair = createAggregatePair(table, generator.generateExpression(GaussDBDataType.BOOLEAN), bypassBug);
            break;
        case 5:
            pair = buildJoinPair(bypassBug);
            break;
        default:
            throw new AssertionError();
        }
        if (pair == null) {
            return;
        }
        verifyPair(pair);
    }

    static TLPQueryPair createWherePair(GaussDBTable table, GaussDBExpression predicate) {
        return createWherePair(table, predicate, false);
    }

    static TLPQueryPair createWherePair(GaussDBTable table, GaussDBExpression predicate, boolean bypassBug) {
        GaussDBSelect original = new GaussDBSelect();
        original.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        original.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS)));

        GaussDBSetOperation unionAll = buildThreeValuedUnion(table, Collections.singletonList(new GaussDBStar()),
                predicate, false, bypassBug);
        return new TLPQueryPair(GaussDBToStringVisitor.asString(original), GaussDBToStringVisitor.asString(unionAll));
    }

    static TLPQueryPair createDistinctPair(GaussDBTable table, GaussDBExpression predicate) {
        return createDistinctPair(table, predicate, false);
    }

    static TLPQueryPair createDistinctPair(GaussDBTable table, GaussDBExpression predicate, boolean bypassBug) {
        GaussDBSelect original = new GaussDBSelect();
        original.setDistinct(true);
        original.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        original.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS)));

        GaussDBSetOperation union = buildThreeValuedUnion(table, Collections.singletonList(new GaussDBStar()),
                predicate, true, bypassBug);
        return new TLPQueryPair(GaussDBToStringVisitor.asString(original), GaussDBToStringVisitor.asString(union));
    }

    static TLPQueryPair createGroupByPair(GaussDBTable table, GaussDBColumn groupCol, GaussDBExpression predicate) {
        return createGroupByPair(table, groupCol, predicate, false);
    }

    static TLPQueryPair createGroupByPair(GaussDBTable table, GaussDBColumn groupCol, GaussDBExpression predicate,
            boolean bypassBug) {
        GaussDBExpression colRef = new GaussDBColumnReference(groupCol, TABLE_ALIAS);
        GaussDBSelect original = new GaussDBSelect();
        original.setFetchColumns(Collections.singletonList(new GaussDBAlias(colRef, "g0")));
        original.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS)));
        original.setGroupByExpressions(Collections.singletonList(colRef));

        GaussDBSetOperation union = buildThreeValuedGroupByUnion(table, colRef, predicate, false, bypassBug);
        return new TLPQueryPair(GaussDBToStringVisitor.asString(original), GaussDBToStringVisitor.asString(union));
    }

    private TLPQueryPair buildHavingPair(GaussDBTable table, boolean bypassBug) {
        GaussDBColumn groupCol = Randomly.fromList(table.getColumns());
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(Collections.singletonList(groupCol));
        generator.setTableAlias(TABLE_ALIAS);
        GaussDBExpression havingPred = generator.generateExpression(GaussDBDataType.BOOLEAN);
        return createHavingPair(table, groupCol, havingPred, bypassBug);
    }

    static TLPQueryPair createHavingPair(GaussDBTable table, GaussDBColumn groupCol, GaussDBExpression havingPred) {
        return createHavingPair(table, groupCol, havingPred, false);
    }

    static TLPQueryPair createHavingPair(GaussDBTable table, GaussDBColumn groupCol, GaussDBExpression havingPred,
            boolean bypassBug) {
        GaussDBExpression colRef = new GaussDBColumnReference(groupCol, TABLE_ALIAS);
        GaussDBAggregate countStar = new GaussDBAggregate(GaussDBAggregate.Function.COUNT,
                Collections.singletonList(new GaussDBStar()), GaussDBDataType.INT);
        List<GaussDBExpression> fetchCols = List.of(new GaussDBAlias(colRef, "g0"),
                new GaussDBAlias(countStar, "cnt"));

        GaussDBSelect original = new GaussDBSelect();
        original.setFetchColumns(fetchCols);
        original.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS)));
        original.setGroupByExpressions(Collections.singletonList(colRef));

        GaussDBSelect isTrue = selectGroupByHavingBranch(table, fetchCols, colRef,
                new GaussDBUnaryOperation(havingPred, GaussDBUnaryOperation.Operator.IS_TRUE, GaussDBDataType.BOOLEAN));
        GaussDBSelect isFalse = selectGroupByHavingBranch(table, fetchCols, colRef,
                buildIsFalseCondition(havingPred, bypassBug));
        GaussDBSelect isNull = selectGroupByHavingBranch(table, fetchCols, colRef,
                new GaussDBUnaryOperation(havingPred, GaussDBUnaryOperation.Operator.IS_NULL, GaussDBDataType.BOOLEAN));

        GaussDBSetOperation firstUnion = new GaussDBSetOperation(isTrue, isFalse,
                GaussDBSetOperation.Operator.UNION_ALL);
        GaussDBSetOperation fullUnion = new GaussDBSetOperation(firstUnion, isNull,
                GaussDBSetOperation.Operator.UNION_ALL);
        return new TLPQueryPair(GaussDBToStringVisitor.asString(original), GaussDBToStringVisitor.asString(fullUnion));
    }

    static TLPQueryPair createAggregatePair(GaussDBTable table, GaussDBExpression predicate) {
        return createAggregatePair(table, predicate, false);
    }

    static TLPQueryPair createAggregatePair(GaussDBTable table, GaussDBExpression predicate, boolean bypassBug) {
        List<GaussDBColumn> numericCols = table.getColumns().stream()
                .filter(c -> c.getType() == GaussDBDataType.INT || c.getType() == GaussDBDataType.DECIMAL)
                .collect(Collectors.toList());
        if (numericCols.isEmpty()) {
            GaussDBAggregate countStar = new GaussDBAggregate(GaussDBAggregate.Function.COUNT,
                    Collections.singletonList(new GaussDBStar()), GaussDBDataType.INT);
            GaussDBSelect original = GaussDBEETOracle.selectAggregateFrom(table, countStar);
            GaussDBSetOperation branches = buildThreeValuedUnion(table,
                    Collections.singletonList(new GaussDBAlias(countStar, "agg0")), predicate, false, bypassBug);
            String partitionedSql = "SELECT CAST(COALESCE(SUM(agg0), 0) AS BIGINT) AS row_count FROM ("
                    + GaussDBToStringVisitor.asString(branches) + ") AS tlp_agg";
            return new TLPQueryPair(GaussDBToStringVisitor.asString(original), partitionedSql);
        }
        GaussDBColumn col = Randomly.fromList(numericCols);
        GaussDBAggregate.Function fn = Randomly.fromOptions(GaussDBAggregate.Function.MIN,
                GaussDBAggregate.Function.MAX, GaussDBAggregate.Function.SUM, GaussDBAggregate.Function.COUNT);
        GaussDBExpression colRef = new GaussDBColumnReference(col, TABLE_ALIAS);
        GaussDBAggregate agg = new GaussDBAggregate(fn, Collections.singletonList(colRef), col.getType());
        GaussDBSelect original = GaussDBEETOracle.selectAggregateFrom(table, agg);
        GaussDBSetOperation branches = buildThreeValuedUnion(table,
                Collections.singletonList(new GaussDBAlias(agg, "agg0")), predicate, false, bypassBug);
        String outerCombine;
        if (fn == GaussDBAggregate.Function.COUNT) {
            outerCombine = "CAST(COALESCE(SUM(agg0), 0) AS BIGINT)";
        } else if (fn == GaussDBAggregate.Function.SUM) {
            outerCombine = "SUM(agg0)";
        } else {
            outerCombine = fn.name() + "(agg0)";
        }
        String partitionedSql = "SELECT " + outerCombine + " AS row_count FROM ("
                + GaussDBToStringVisitor.asString(branches) + ") AS tlp_agg";
        return new TLPQueryPair(GaussDBToStringVisitor.asString(original), partitionedSql);
    }

    private TLPQueryPair buildJoinPair(boolean bypassBug) {
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
                GaussDBJoin.JoinType.RIGHT);
        GaussDBExpression onPred = new GaussDBBinaryOperation(new GaussDBColumnReference(leftCol, "l"),
                new GaussDBColumnReference(rightCol, "r"), GaussDBBinaryOperation.Operator.getRandomComparison(),
                GaussDBDataType.BOOLEAN);

        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(leftTable.getColumns());
        generator.setTableAlias("l");
        GaussDBExpression wherePred = generator.generateExpression(GaussDBDataType.BOOLEAN);

        GaussDBSelect original = GaussDBEETOracle.selectStarJoin(leftTable, rightTable, joinType, onPred);
        GaussDBSelect isTrue = GaussDBEETOracle.selectStarJoin(leftTable, rightTable, joinType, onPred);
        isTrue.setWhereClause(
                new GaussDBUnaryOperation(wherePred, GaussDBUnaryOperation.Operator.IS_TRUE, GaussDBDataType.BOOLEAN));
        GaussDBSelect isFalse = GaussDBEETOracle.selectStarJoin(leftTable, rightTable, joinType, onPred);
        isFalse.setWhereClause(buildIsFalseCondition(wherePred, bypassBug));
        GaussDBSelect isNull = GaussDBEETOracle.selectStarJoin(leftTable, rightTable, joinType, onPred);
        isNull.setWhereClause(
                new GaussDBUnaryOperation(wherePred, GaussDBUnaryOperation.Operator.IS_NULL, GaussDBDataType.BOOLEAN));

        GaussDBSetOperation firstUnion = new GaussDBSetOperation(isTrue, isFalse,
                GaussDBSetOperation.Operator.UNION_ALL);
        GaussDBSetOperation fullUnion = new GaussDBSetOperation(firstUnion, isNull,
                GaussDBSetOperation.Operator.UNION_ALL);
        return new TLPQueryPair(GaussDBToStringVisitor.asString(original), GaussDBToStringVisitor.asString(fullUnion));
    }

    private static GaussDBSetOperation buildThreeValuedUnion(GaussDBTable table, List<GaussDBExpression> fetchCols,
            GaussDBExpression predicate, boolean distinct, boolean bypassBug) {
        GaussDBSetOperation.Operator op = distinct ? GaussDBSetOperation.Operator.UNION
                : GaussDBSetOperation.Operator.UNION_ALL;
        GaussDBSelect isTrue = selectBranch(table, fetchCols,
                new GaussDBUnaryOperation(predicate, GaussDBUnaryOperation.Operator.IS_TRUE, GaussDBDataType.BOOLEAN),
                distinct);
        GaussDBSelect isFalse = selectBranch(table, fetchCols, buildIsFalseCondition(predicate, bypassBug), distinct);
        GaussDBSelect isNull = selectBranch(table, fetchCols,
                new GaussDBUnaryOperation(predicate, GaussDBUnaryOperation.Operator.IS_NULL, GaussDBDataType.BOOLEAN),
                distinct);
        return new GaussDBSetOperation(new GaussDBSetOperation(isTrue, isFalse, op), isNull, op);
    }

    private static GaussDBSetOperation buildThreeValuedGroupByUnion(GaussDBTable table, GaussDBExpression colRef,
            GaussDBExpression predicate, boolean distinct, boolean bypassBug) {
        List<GaussDBExpression> fetchCols = Collections.singletonList(new GaussDBAlias(colRef, "g0"));
        GaussDBSelect isTrue = selectBranch(table, fetchCols,
                new GaussDBUnaryOperation(predicate, GaussDBUnaryOperation.Operator.IS_TRUE, GaussDBDataType.BOOLEAN),
                distinct);
        isTrue.setGroupByExpressions(Collections.singletonList(colRef));
        GaussDBSelect isFalse = selectBranch(table, fetchCols, buildIsFalseCondition(predicate, bypassBug), distinct);
        isFalse.setGroupByExpressions(Collections.singletonList(colRef));
        GaussDBSelect isNull = selectBranch(table, fetchCols,
                new GaussDBUnaryOperation(predicate, GaussDBUnaryOperation.Operator.IS_NULL, GaussDBDataType.BOOLEAN),
                distinct);
        isNull.setGroupByExpressions(Collections.singletonList(colRef));
        return new GaussDBSetOperation(new GaussDBSetOperation(isTrue, isFalse, GaussDBSetOperation.Operator.UNION),
                isNull, GaussDBSetOperation.Operator.UNION);
    }

    private static GaussDBExpression buildIsFalseCondition(GaussDBExpression predicate,
            boolean bypassBooleanTestRangeBug) {
        GaussDBExpression isFalse = new GaussDBUnaryOperation(predicate, GaussDBUnaryOperation.Operator.IS_FALSE,
                GaussDBDataType.BOOLEAN);
        if (!bypassBooleanTestRangeBug) {
            return isFalse;
        }
        GaussDBExpression isNotNull = new GaussDBUnaryOperation(predicate, GaussDBUnaryOperation.Operator.IS_NOT_NULL,
                GaussDBDataType.BOOLEAN);
        return new GaussDBBinaryOperation(isFalse, isNotNull, GaussDBBinaryOperation.Operator.AND,
                GaussDBDataType.BOOLEAN);
    }

    private static GaussDBSelect selectBranch(GaussDBTable table, List<GaussDBExpression> fetchCols,
            GaussDBExpression whereClause, boolean distinct) {
        GaussDBSelect select = new GaussDBSelect();
        select.setDistinct(distinct);
        select.setFetchColumns(fetchCols);
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS)));
        select.setWhereClause(whereClause);
        return select;
    }

    private static GaussDBSelect selectGroupByHavingBranch(GaussDBTable table, List<GaussDBExpression> fetchCols,
            GaussDBExpression groupCol, GaussDBExpression havingClause) {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(fetchCols);
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS)));
        select.setGroupByExpressions(Collections.singletonList(groupCol));
        select.setHavingClause(havingClause);
        return select;
    }

    private void verifyPair(TLPQueryPair pair) throws SQLException {
        lastQueryString = pair.getOriginalQuery();
        SQLConnection connection = state.getConnection();
        try (Statement statement = connection.createStatement()) {
            List<String> originalRows = executeAndGetRows(statement, pair.getOriginalQuery(), errors);
            List<String> partitionedRows = executeAndGetRows(statement, pair.getPartitionedQuery(), errors);
            if (areEquivalent(originalRows, partitionedRows)) {
                return;
            }
            // Re-run once to rule out nondeterministic expressions.
            originalRows = executeAndGetRows(statement, pair.getOriginalQuery(), errors);
            partitionedRows = executeAndGetRows(statement, pair.getPartitionedQuery(), errors);
            if (!areEquivalent(originalRows, partitionedRows)) {
                reproducer = new GaussDBTLPReproducer(pair);
                throw new AssertionError("TLP mismatch\nOriginal: " + pair.getOriginalQuery() + "\nPartitioned: "
                        + pair.getPartitionedQuery() + "\nOriginalResult: " + originalRows + "\nPartitionedResult: "
                        + partitionedRows);
            }
        }
    }

    static List<String> executeAndGetRows(Statement statement, String query, ExpectedErrors errors)
            throws SQLException {
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
            if (errors != null && errors.errorIsExpected(e.getMessage())) {
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

    @Override
    public Reproducer<GaussDBGlobalState> getLastReproducer() {
        return reproducer;
    }

    @Override
    public String getLastQueryString() {
        return lastQueryString;
    }

}
