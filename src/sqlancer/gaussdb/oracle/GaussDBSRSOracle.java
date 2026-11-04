package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import sqlancer.gaussdb.ast.GaussDBBinaryOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;
import sqlancer.gaussdb.oracle.GaussDBSRSReproducer.SRSType;

/**
 * Set Relation / Join Implication Reasoning (SRS, SIGMOD'26 / ICDE'26) oracle for GaussDB.
 *
 * <p>
 * Evaluates relational set-implication and partitioning invariants on multi-table joins:
 * 1. Inclusion Invariant: Q_inner(l, r) is a subset of Q_left(l, r).
 * 2. Disjoint & Exhaustive Partitioning Invariant: Q_semi(l, r) ∩ Q_anti(l, r) = ∅ and Q_semi ∪ Q_anti = Q_base.
 * 3. Anti-Join Equivalence Invariant: NOT EXISTS ≡ LEFT JOIN ... WHERE r.marker IS NULL.
 *
 * Catches deep optimizer bugs in subquery pullup, uniquecheck elimination, semi/anti join transformations,
 * and outer join predicate pushdown.
 * </p>
 */
public class GaussDBSRSOracle implements TestOracle<GaussDBGlobalState> {

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBSRSReproducer reproducer;

    public GaussDBSRSOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        GaussDBTable leftTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBTable rightTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());

        GaussDBExpression onPred = generateJoinPredicate(leftTable, rightTable);
        if (onPred == null) {
            return;
        }

        GaussDBExpression wherePred = generateWherePredicate(leftTable);
        String onPredSql = GaussDBToStringVisitor.asString(onPred);
        String wherePredSql = GaussDBToStringVisitor.asString(wherePred);

        String selectCols = leftTable.getColumns().stream()
                .map(c -> "l." + c.getName())
                .collect(Collectors.joining(", "));

        int mode = Randomly.fromOptions(0, 1, 2);
        SQLConnection connection = state.getConnection();

        try (Statement statement = connection.createStatement()) {
            if (mode == 0) {
                // Invariant 1: Inclusion (INNER JOIN ⊆ LEFT JOIN)
                String innerSql = "SELECT DISTINCT " + selectCols + " FROM " + leftTable.getName()
                        + " AS l INNER JOIN " + rightTable.getName() + " AS r ON (" + onPredSql + ") WHERE ("
                        + wherePredSql + ")";
                String leftSql = "SELECT DISTINCT " + selectCols + " FROM " + leftTable.getName()
                        + " AS l LEFT JOIN " + rightTable.getName() + " AS r ON (" + onPredSql + ") WHERE ("
                        + wherePredSql + ")";

                Set<String> innerResults = new HashSet<>(executeAndGetRows(statement, innerSql));
                Set<String> leftResults = new HashSet<>(executeAndGetRows(statement, leftSql));

                if (!leftResults.containsAll(innerResults)) {
                    // Retry once to eliminate non-deterministic expressions
                    innerResults = new HashSet<>(executeAndGetRows(statement, innerSql));
                    leftResults = new HashSet<>(executeAndGetRows(statement, leftSql));
                    if (!leftResults.containsAll(innerResults)) {
                        Set<String> missing = new HashSet<>(innerResults);
                        missing.removeAll(leftResults);
                        reproducer = new GaussDBSRSReproducer(SRSType.INCLUSION, innerSql, leftSql, null);
                        throw new AssertionError("SRS Inclusion Mismatch (Inner Join rows not in Left Join)\n"
                                + "InnerQuery:  " + innerSql + "\n"
                                + "LeftQuery:   " + leftSql + "\n"
                                + "InnerRows:   " + innerResults.size() + "\n"
                                + "LeftRows:    " + leftResults.size() + "\n"
                                + "MissingRows: " + missing);
                    }
                }
            } else if (mode == 1) {
                // Invariant 2: Semi / Anti Join Disjoint Partitioning
                String semiSql = "SELECT DISTINCT " + selectCols + " FROM " + leftTable.getName()
                        + " AS l WHERE (" + wherePredSql + ") AND EXISTS (SELECT 1 FROM " + rightTable.getName()
                        + " AS r WHERE " + onPredSql + ")";
                String antiSql = "SELECT DISTINCT " + selectCols + " FROM " + leftTable.getName()
                        + " AS l WHERE (" + wherePredSql + ") AND NOT EXISTS (SELECT 1 FROM " + rightTable.getName()
                        + " AS r WHERE " + onPredSql + ")";
                String baseSql = "SELECT DISTINCT " + selectCols + " FROM " + leftTable.getName()
                        + " AS l WHERE (" + wherePredSql + ")";

                Set<String> semiResults = new HashSet<>(executeAndGetRows(statement, semiSql));
                Set<String> antiResults = new HashSet<>(executeAndGetRows(statement, antiSql));
                Set<String> baseResults = new HashSet<>(executeAndGetRows(statement, baseSql));

                Set<String> intersection = new HashSet<>(semiResults);
                intersection.retainAll(antiResults);

                Set<String> union = new HashSet<>(semiResults);
                union.addAll(antiResults);

                if (!intersection.isEmpty() || !union.equals(baseResults)) {
                    // Retry once
                    semiResults = new HashSet<>(executeAndGetRows(statement, semiSql));
                    antiResults = new HashSet<>(executeAndGetRows(statement, antiSql));
                    baseResults = new HashSet<>(executeAndGetRows(statement, baseSql));

                    intersection = new HashSet<>(semiResults);
                    intersection.retainAll(antiResults);
                    union = new HashSet<>(semiResults);
                    union.addAll(antiResults);

                    if (!intersection.isEmpty() || !union.equals(baseResults)) {
                        reproducer = new GaussDBSRSReproducer(SRSType.SEMI_ANTI_PARTITION, semiSql, antiSql, baseSql);
                        throw new AssertionError("SRS Semi/Anti Partition Mismatch (Exhaustive & Disjoint violation)\n"
                                + "SemiQuery:  " + semiSql + "\n"
                                + "AntiQuery:  " + antiSql + "\n"
                                + "BaseQuery:  " + baseSql + "\n"
                                + "SemiRows:   " + semiResults.size() + "\n"
                                + "AntiRows:   " + antiResults.size() + "\n"
                                + "BaseRows:   " + baseResults.size() + "\n"
                                + "DisjointViolations: " + intersection + "\n"
                                + "UnionMatchesBase:   " + union.equals(baseResults));
                    }
                }
            } else {
                // Invariant 3: Anti-Join Equivalence (NOT EXISTS ≡ LEFT JOIN IS NULL)
                String notExistsSql = "SELECT DISTINCT " + selectCols + " FROM " + leftTable.getName()
                        + " AS l WHERE (" + wherePredSql + ") AND NOT EXISTS (SELECT 1 FROM " + rightTable.getName()
                        + " AS r WHERE " + onPredSql + ")";
                String leftNullSql = "SELECT DISTINCT " + selectCols + " FROM " + leftTable.getName()
                        + " AS l LEFT JOIN (SELECT 1 AS srs_marker, * FROM " + rightTable.getName()
                        + ") AS r ON (" + onPredSql + ") WHERE (" + wherePredSql + ") AND r.srs_marker IS NULL";

                Set<String> notExistsResults = new HashSet<>(executeAndGetRows(statement, notExistsSql));
                Set<String> leftNullResults = new HashSet<>(executeAndGetRows(statement, leftNullSql));

                if (!notExistsResults.equals(leftNullResults)) {
                    // Retry once
                    notExistsResults = new HashSet<>(executeAndGetRows(statement, notExistsSql));
                    leftNullResults = new HashSet<>(executeAndGetRows(statement, leftNullSql));
                    if (!notExistsResults.equals(leftNullResults)) {
                        reproducer = new GaussDBSRSReproducer(SRSType.ANTI_JOIN_EQUIVALENCE, notExistsSql, leftNullSql, null);
                        throw new AssertionError("SRS Anti-Join Equivalence Mismatch (NOT EXISTS vs LEFT JOIN IS NULL)\n"
                                + "NotExistsQuery: " + notExistsSql + "\n"
                                + "LeftNullQuery:  " + leftNullSql + "\n"
                                + "NotExistsRows:  " + notExistsResults.size() + "\n"
                                + "LeftNullRows:   " + leftNullResults.size());
                    }
                }
            }
        }
    }

    private GaussDBExpression generateJoinPredicate(GaussDBTable leftTable, GaussDBTable rightTable) {
        List<GaussDBColumn> leftCols = leftTable.getColumns();
        List<GaussDBColumn> rightCols = rightTable.getColumns();

        List<GaussDBColumn> matchedLeft = new ArrayList<>();
        List<GaussDBColumn> matchedRight = new ArrayList<>();

        for (GaussDBColumn lc : leftCols) {
            for (GaussDBColumn rc : rightCols) {
                if (lc.getType() == rc.getType()) {
                    matchedLeft.add(lc);
                    matchedRight.add(rc);
                    break;
                }
            }
        }

        if (matchedLeft.isEmpty()) {
            return null;
        }

        int idx = (int) Randomly.getNotCachedInteger(0, matchedLeft.size());
        GaussDBColumn lCol = matchedLeft.get(idx);
        GaussDBColumn rCol = matchedRight.get(idx);

        GaussDBBinaryOperation.Operator op = Randomly.fromOptions(
                GaussDBBinaryOperation.Operator.EQUALS,
                GaussDBBinaryOperation.Operator.EQUALS,
                GaussDBBinaryOperation.Operator.EQUALS,
                GaussDBBinaryOperation.Operator.LESS,
                GaussDBBinaryOperation.Operator.GREATER,
                GaussDBBinaryOperation.Operator.NOT_EQUALS);

        return new GaussDBBinaryOperation(
                new GaussDBColumnReference(lCol, "l"),
                new GaussDBColumnReference(rCol, "r"),
                op,
                GaussDBDataType.BOOLEAN);
    }

    private GaussDBExpression generateWherePredicate(GaussDBTable leftTable) {
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(leftTable.getColumns());
        generator.setTableAlias("l");
        return generator.generateExpression(GaussDBDataType.BOOLEAN);
    }

    private List<String> executeAndGetRows(Statement statement, String query) throws SQLException {
        List<String> results = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery(query)) {
            int columnCount = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.add(GaussDBEETOracle.normalizeCellValue(resultSet.getString(i)));
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

    @Override
    public Reproducer<GaussDBGlobalState> getLastReproducer() {
        return reproducer;
    }

    @Override
    public String getLastQueryString() {
        return null;
    }
}
