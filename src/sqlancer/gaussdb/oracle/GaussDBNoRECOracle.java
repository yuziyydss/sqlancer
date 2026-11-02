package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

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
import sqlancer.gaussdb.ast.GaussDBCastOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBJoin;
import sqlancer.gaussdb.ast.GaussDBSelect;
import sqlancer.gaussdb.ast.GaussDBStar;
import sqlancer.gaussdb.ast.GaussDBTableReference;
import sqlancer.gaussdb.ast.GaussDBUnaryOperation;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

/**
 * Non-Optimizing Reference Engine Construction (NoREC, ESEC/FSE'20) oracle for GaussDB.
 */
public class GaussDBNoRECOracle implements TestOracle<GaussDBGlobalState> {

    private static final String TABLE_ALIAS = "t";

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBNoRECReproducer reproducer;
    private String lastQueryString;

    public GaussDBNoRECOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public static final class NoRECQueryPair {

        private final String optimizedQuery;
        private final String unoptimizedQuery;

        NoRECQueryPair(String optimizedQuery, String unoptimizedQuery) {
            this.optimizedQuery = optimizedQuery;
            this.unoptimizedQuery = unoptimizedQuery;
        }

        public String getOptimizedQuery() {
            return optimizedQuery;
        }

        public String getUnoptimizedQuery() {
            return unoptimizedQuery;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        NoRECQueryPair pair = Randomly.getBooleanWithSmallProbability() ? buildJoinPair() : buildSingleTablePair();
        if (pair == null) {
            return;
        }
        verifyPair(pair);
    }

    private NoRECQueryPair buildSingleTablePair() {
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(table.getColumns());
        generator.setTableAlias(TABLE_ALIAS);
        GaussDBExpression predicate = generator.generateExpression(GaussDBDataType.BOOLEAN);
        return createSingleTablePair(table, predicate);
    }

    private NoRECQueryPair buildJoinPair() {
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
        return createJoinPair(leftTable, rightTable, joinType, onPred, wherePred);
    }

    static NoRECQueryPair createSingleTablePair(GaussDBTable table, GaussDBExpression predicate) {
        GaussDBExpression fromRef = new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS);
        return createPairFromClauses(Collections.singletonList(fromRef), Collections.emptyList(), predicate);
    }

    static NoRECQueryPair createJoinPair(GaussDBTable leftTable, GaussDBTable rightTable, GaussDBJoin.JoinType joinType,
            GaussDBExpression onPredicate, GaussDBExpression wherePredicate) {
        GaussDBExpression leftRef = new GaussDBAlias(new GaussDBTableReference(leftTable), "l");
        GaussDBExpression rightRef = new GaussDBAlias(new GaussDBTableReference(rightTable), "r");
        GaussDBJoin join = new GaussDBJoin(leftRef, rightRef, joinType, onPredicate);
        return createPairFromClauses(Collections.singletonList(leftRef), Collections.singletonList(join),
                wherePredicate);
    }

    private static NoRECQueryPair createPairFromClauses(List<GaussDBExpression> fromList,
            List<GaussDBJoin> joinList, GaussDBExpression predicate) {
        GaussDBAggregate countStar = new GaussDBAggregate(GaussDBAggregate.Function.COUNT,
                Collections.singletonList(new GaussDBStar()), GaussDBDataType.INT);
        GaussDBSelect optimized = new GaussDBSelect();
        optimized.setFetchColumns(Collections.singletonList(countStar));
        optimized.setFromList(fromList);
        optimized.setJoinClauses(joinList);
        optimized.setWhereClause(predicate);

        GaussDBExpression isTrue = new GaussDBUnaryOperation(predicate, GaussDBUnaryOperation.Operator.IS_TRUE,
                GaussDBDataType.BOOLEAN);
        GaussDBExpression castInt = new GaussDBCastOperation(isTrue, GaussDBDataType.INT);
        GaussDBSelect innerSelect = new GaussDBSelect();
        innerSelect.setFetchColumns(Collections.singletonList(new GaussDBAlias(castInt, "c")));
        innerSelect.setFromList(fromList);
        innerSelect.setJoinClauses(joinList);

        String optimizedSql = GaussDBToStringVisitor.asString(optimized);
        String unoptimizedSql = "SELECT COALESCE(SUM(c), 0) FROM (" + GaussDBToStringVisitor.asString(innerSelect)
                + ") AS norec_sub";
        return new NoRECQueryPair(optimizedSql, unoptimizedSql);
    }

    private void verifyPair(NoRECQueryPair pair) throws SQLException {
        lastQueryString = pair.getOptimizedQuery();
        SQLConnection connection = state.getConnection();
        try (Statement statement = connection.createStatement()) {
            long optimizedCount = executeCount(statement, pair.getOptimizedQuery(), errors);
            long unoptimizedCount = executeCount(statement, pair.getUnoptimizedQuery(), errors);
            if (optimizedCount == unoptimizedCount) {
                return;
            }
            // Re-run once to rule out nondeterministic expressions.
            optimizedCount = executeCount(statement, pair.getOptimizedQuery(), errors);
            unoptimizedCount = executeCount(statement, pair.getUnoptimizedQuery(), errors);
            if (optimizedCount != unoptimizedCount) {
                if (state.getDbmsSpecificOptions() != null && state.getDbmsSpecificOptions().bypassBooleanTestRangeBug) {
                    String optSql = pair.getOptimizedQuery().toLowerCase();
                    if ((optSql.contains("least") || optSql.contains("greatest")) && optSql.contains("between symmetric")) {
                        return; // Known Bug #18: Expression Flatten Optimized folds self BETWEEN SYMMETRIC to false, breaking 3VL under LEAST/GREATEST
                    }
                }
                reproducer = new GaussDBNoRECReproducer(pair);
                throw new AssertionError("NoREC count mismatch\nOptimized: " + pair.getOptimizedQuery()
                        + "\nUnoptimized: " + pair.getUnoptimizedQuery() + "\nOptimizedCount: " + optimizedCount
                        + "\nUnoptimizedCount: " + unoptimizedCount);
            }
        }
    }

    static long executeCount(Statement statement, String query, ExpectedErrors errors) throws SQLException {
        try (ResultSet rs = statement.executeQuery(query)) {
            if (!rs.next()) {
                return 0L;
            }
            return rs.getLong(1);
        } catch (SQLException e) {
            if (errors != null && errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
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
