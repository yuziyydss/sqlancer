package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import sqlancer.gaussdb.ast.GaussDBAlias;
import sqlancer.gaussdb.ast.GaussDBBinaryOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBJoin;
import sqlancer.gaussdb.ast.GaussDBSelect;
import sqlancer.gaussdb.ast.GaussDBStar;
import sqlancer.gaussdb.ast.GaussDBTableReference;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

/**
 * Cardinality Estimation Restriction Testing (CERT, ICSE'24) oracle for GaussDB.
 *
 * <p>
 * Evaluates the mathematical monotonicity principle of the Cost-Based Optimizer (CBO):
 * For any base query Q1: {@code SELECT * FROM T WHERE P1} and restricted query
 * Q2: {@code SELECT * FROM T WHERE (P1) AND (P2)}, the relation Q2 ⊆ Q1 holds logically.
 * Therefore, any sound cardinality estimator must satisfy:
 * {@code EstimatedRows(Q2) <= EstimatedRows(Q1) + tolerance}.
 *
 * Any violation (EstimatedRows(Q2) > EstimatedRows(Q1) + 1) exposes plan distortion
 * and optimizer estimation bugs.
 * </p>
 */
public class GaussDBCERTOracle implements TestOracle<GaussDBGlobalState> {

    private static final String TABLE_ALIAS = "t";
    private static final Pattern ROWS_PATTERN = Pattern.compile("rows=([0-9]+)");

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBCERTReproducer reproducer;

    public GaussDBCERTOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public static final class CERTQueryPair {
        private final String baseQuery;
        private final String restrictedQuery;

        public CERTQueryPair(String baseQuery, String restrictedQuery) {
            this.baseQuery = baseQuery;
            this.restrictedQuery = restrictedQuery;
        }

        public String getBaseQuery() {
            return baseQuery;
        }

        public String getRestrictedQuery() {
            return restrictedQuery;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        CERTQueryPair pair = Randomly.getBooleanWithSmallProbability() ? buildJoinPair() : buildSingleTablePair();
        if (pair == null) {
            return;
        }
        verifyPair(pair);
    }

    private CERTQueryPair buildSingleTablePair() {
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(table.getColumns());
        generator.setTableAlias(TABLE_ALIAS);

        GaussDBExpression pred1 = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression pred2 = generator.generateExpression(GaussDBDataType.BOOLEAN);

        return createSingleTablePair(table, pred1, pred2);
    }

    private CERTQueryPair buildJoinPair() {
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

        GaussDBJoin.JoinType joinType = Randomly.fromOptions(GaussDBJoin.JoinType.INNER, GaussDBJoin.JoinType.LEFT);
        GaussDBExpression onPred = new GaussDBBinaryOperation(new GaussDBColumnReference(leftCol, "l"),
                new GaussDBColumnReference(rightCol, "r"), GaussDBBinaryOperation.Operator.getRandomComparison(),
                GaussDBDataType.BOOLEAN);

        GaussDBExpressionGenerator genL = new GaussDBExpressionGenerator(state);
        genL.setColumns(leftTable.getColumns());
        genL.setTableAlias("l");
        GaussDBExpression pred1 = genL.generateExpression(GaussDBDataType.BOOLEAN);

        GaussDBExpressionGenerator genR = new GaussDBExpressionGenerator(state);
        genR.setColumns(rightTable.getColumns());
        genR.setTableAlias("r");
        GaussDBExpression pred2 = genR.generateExpression(GaussDBDataType.BOOLEAN);

        return createJoinPair(leftTable, rightTable, joinType, onPred, pred1, pred2);
    }

    static CERTQueryPair createSingleTablePair(GaussDBTable table, GaussDBExpression pred1, GaussDBExpression pred2) {
        GaussDBExpression fromRef = new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS);

        GaussDBSelect q1 = new GaussDBSelect();
        q1.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        q1.setFromList(Collections.singletonList(fromRef));
        q1.setWhereClause(pred1);

        GaussDBExpression restrictedPred = new GaussDBBinaryOperation(pred1, pred2,
                GaussDBBinaryOperation.Operator.AND, GaussDBDataType.BOOLEAN);
        GaussDBSelect q2 = new GaussDBSelect();
        q2.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        q2.setFromList(Collections.singletonList(fromRef));
        q2.setWhereClause(restrictedPred);

        return new CERTQueryPair(GaussDBToStringVisitor.asString(q1), GaussDBToStringVisitor.asString(q2));
    }

    static CERTQueryPair createJoinPair(GaussDBTable leftTable, GaussDBTable rightTable,
            GaussDBJoin.JoinType joinType, GaussDBExpression onPredicate,
            GaussDBExpression pred1, GaussDBExpression pred2) {
        GaussDBExpression leftRef = new GaussDBAlias(new GaussDBTableReference(leftTable), "l");
        GaussDBExpression rightRef = new GaussDBAlias(new GaussDBTableReference(rightTable), "r");
        GaussDBJoin join = new GaussDBJoin(leftRef, rightRef, joinType, onPredicate);

        GaussDBSelect q1 = new GaussDBSelect();
        q1.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        q1.setFromList(Collections.singletonList(leftRef));
        q1.setJoinList(Collections.singletonList(join));
        q1.setWhereClause(pred1);

        GaussDBExpression restrictedPred = new GaussDBBinaryOperation(pred1, pred2,
                GaussDBBinaryOperation.Operator.AND, GaussDBDataType.BOOLEAN);
        GaussDBSelect q2 = new GaussDBSelect();
        q2.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        q2.setFromList(Collections.singletonList(leftRef));
        q2.setJoinList(Collections.singletonList(join));
        q2.setWhereClause(restrictedPred);

        return new CERTQueryPair(GaussDBToStringVisitor.asString(q1), GaussDBToStringVisitor.asString(q2));
    }

    private void verifyPair(CERTQueryPair pair) throws SQLException {
        SQLConnection connection = state.getConnection();
        try (Statement statement = connection.createStatement()) {
            Long est1;
            Long est2;
            try {
                est1 = extractEstimatedRows(statement, pair.getBaseQuery());
                est2 = extractEstimatedRows(statement, pair.getRestrictedQuery());
            } catch (SQLException e) {
                if (errors.errorIsExpected(e.getMessage())) {
                    throw new IgnoreMeException();
                }
                throw e;
            }

            if (est1 == null || est2 == null) {
                return;
            }

            // Monotonicity check with 1-row margin for integer rounding
            if (est2 > est1 + 1) {
                reproducer = new GaussDBCERTReproducer(pair.getBaseQuery(), pair.getRestrictedQuery(), est1, est2);
                state.getState().getLocalState().log(
                        String.format("CERT Violation detected: Base Est=%d, Restricted Est=%d%nBase: %s%nRestricted: %s",
                                est1, est2, pair.getBaseQuery(), pair.getRestrictedQuery()));
                throw new AssertionError(String.format(
                        "CERT Monotonicity Violation: Restricted query estimated %d rows > Base query estimated %d rows!%nBase: %s%nRestricted: %s",
                        est2, est1, pair.getBaseQuery(), pair.getRestrictedQuery()));
            }
        }
    }

    public static Long extractEstimatedRows(Statement statement, String query) throws SQLException {
        try {
            statement.execute("SET explain_perf_mode = normal");
        } catch (SQLException ignored) {
        }
        try (ResultSet rs = statement.executeQuery("EXPLAIN " + query)) {
            while (rs.next()) {
                String line = rs.getString(1);
                Matcher matcher = ROWS_PATTERN.matcher(line);
                if (matcher.find()) {
                    return Long.parseLong(matcher.group(1));
                }
            }
        }
        return null;
    }

    @Override
    public Reproducer<GaussDBGlobalState> getLastReproducer() {
        return reproducer;
    }
}
