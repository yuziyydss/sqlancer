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
import sqlancer.gaussdb.ast.GaussDBAlias;
import sqlancer.gaussdb.ast.GaussDBAnyAllOperation;
import sqlancer.gaussdb.ast.GaussDBBinaryOperation;
import sqlancer.gaussdb.ast.GaussDBCaseOperation;
import sqlancer.gaussdb.ast.GaussDBCastOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBExists;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBExpressionBag;
import sqlancer.gaussdb.ast.GaussDBInSubquery;
import sqlancer.gaussdb.ast.GaussDBJoin;
import sqlancer.gaussdb.ast.GaussDBSelect;
import sqlancer.gaussdb.ast.GaussDBSetOperation;
import sqlancer.gaussdb.ast.GaussDBStar;
import sqlancer.gaussdb.ast.GaussDBSubquery;
import sqlancer.gaussdb.ast.GaussDBTableReference;
import sqlancer.gaussdb.ast.GaussDBUnaryOperation;
import sqlancer.gaussdb.ast.GaussDBWindowFrame;
import sqlancer.gaussdb.ast.GaussDBWindowFunction;
import sqlancer.gaussdb.ast.GaussDBWithClause;
import sqlancer.gaussdb.ast.GaussDBWithClauseGroup;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

public class GaussDBEETOracle implements TestOracle<GaussDBGlobalState> {

    private static final String TABLE_ALIAS = "t";

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBEETReproducer reproducer;
    private String lastOriginalQuery;

    public GaussDBEETOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());

        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(table.getColumns());
        generator.setTableAlias(TABLE_ALIAS);

        switch (Randomly.fromOptions(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22,
                23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35)) {
        case 0:
            checkSelectList(table, generator);
            return;
        case 1:
            checkWhere(table, generator);
            return;
        case 2:
            checkHaving(table);
            return;
        case 3:
            checkJoinOn();
            return;
        case 4:
            checkCte(table, generator);
            return;
        case 5:
            checkSubquery();
            return;
        case 6:
            checkInSubquery();
            return;
        case 7:
            checkAnyAllSubquery();
            return;
        case 8:
            checkDerivedTable(table, generator);
            return;
        case 9:
            checkNotExists();
            return;
        case 10:
            checkNotInSubquery();
            return;
        case 11:
            checkScalarSubquery();
            return;
        case 12:
            checkDistinctSelectList(table, generator);
            return;
        case 13:
            checkGroupByExpression(table, generator);
            return;
        case 14:
            checkCountAggregate(table);
            return;
        case 15:
            checkWindowFunction(table, generator);
            return;
        case 16:
            checkOrderByExpression(table, generator);
            return;
        case 17:
            checkSetOperation(table, generator);
            return;
        case 18:
            checkLimitExpression(table);
            return;
        case 19:
            checkOffsetExpression(table);
            return;
        case 20:
            checkSelectListScalarSubquery(table, generator);
            return;
        case 21:
            checkSelectListExists(table, generator);
            return;
        case 22:
            checkAggregateArgument(table, generator);
            return;
        case 23:
            checkMultiColumnGroupBy(table, generator);
            return;
        case 24:
            checkNestedCte(table, generator);
            return;
        case 25:
            checkThreeTableJoin();
            return;
        case 26:
            checkNestedDerivedTable(table, generator);
            return;
        case 27:
            checkLagLeadWindowFunction(table, generator);
            return;
        case 28:
            checkMultiColumnOrderBy(table, generator);
            return;
        case 29:
            checkValueWindowFunction(table, generator);
            return;
        case 30:
            checkMixedThreeTableJoin();
            return;
        case 31:
            checkJoinDerivedTable();
            return;
        case 32:
            checkFramedWindowFunction(table, generator);
            return;
        case 33:
            checkRecursiveCte();
            return;
        case 34:
            checkMultipleCtes(table, generator);
            return;
        case 35:
            checkFilteredWindowFunction(table, generator);
            return;
        default:
            throw new AssertionError("Unsupported EET mode");
        }
    }

    private void checkSelectList(GaussDBTable table, GaussDBExpressionGenerator generator) throws SQLException {
        GaussDBDataType type = GaussDBDataType.getRandomType();
        GaussDBExpression expression = generator.generateExpression(type);
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        String originalQuery = GaussDBToStringVisitor.asString(selectValueFrom(table, expression));
        String transformedQuery = GaussDBToStringVisitor
                .asString(selectValueFrom(table, transform(expression, condition)));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkWhere(GaussDBTable table, GaussDBExpressionGenerator generator) throws SQLException {
        GaussDBExpression predicate = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        String originalQuery = GaussDBToStringVisitor.asString(selectStarWhere(table, predicate));
        String transformedQuery = GaussDBToStringVisitor
                .asString(selectStarWhere(table, transform(predicate, condition)));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkHaving(GaussDBTable table) throws SQLException {
        GaussDBColumn column = Randomly.fromList(table.getColumns());
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(Collections.singletonList(column));
        generator.setTableAlias(TABLE_ALIAS);

        GaussDBExpression predicate = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        String originalQuery = GaussDBToStringVisitor.asString(selectGroupByHaving(table, column, predicate));
        String transformedQuery = GaussDBToStringVisitor
                .asString(selectGroupByHaving(table, column, transform(predicate, condition)));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkJoinOn() throws SQLException {
        GaussDBTable leftTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBTable rightTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBColumn[] columns = getSameTypeColumnPair(leftTable, rightTable);
        if (columns == null) {
            return;
        }

        GaussDBJoin.JoinType joinType = Randomly.fromOptions(GaussDBJoin.JoinType.INNER, GaussDBJoin.JoinType.LEFT,
                GaussDBJoin.JoinType.RIGHT, GaussDBJoin.JoinType.FULL);
        GaussDBExpression predicate = comparison(columns[0], "l", columns[1], "r", joinType == GaussDBJoin.JoinType.FULL
                ? GaussDBBinaryOperation.Operator.EQUALS : GaussDBBinaryOperation.Operator.getRandomComparison());
        GaussDBExpression condition = comparison(columns[0], "l", columns[1], "r",
                GaussDBBinaryOperation.Operator.getRandomComparison());

        String originalQuery = GaussDBToStringVisitor
                .asString(selectStarJoin(leftTable, rightTable, joinType, predicate));
        String transformedQuery = GaussDBToStringVisitor.asString(selectStarJoin(leftTable, rightTable, joinType,
                transformJoinPredicate(joinType, predicate, condition)));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkJoinDerivedTable() throws SQLException {
        GaussDBTable leftTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBTable rightTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBColumn[] columns = getSameTypeColumnPair(leftTable, rightTable);
        if (columns == null) {
            return;
        }

        GaussDBJoin.JoinType joinType = Randomly.fromOptions(GaussDBJoin.JoinType.INNER, GaussDBJoin.JoinType.LEFT,
                GaussDBJoin.JoinType.RIGHT, GaussDBJoin.JoinType.FULL);
        GaussDBExpression predicate = comparison(columns[0], "l", columns[1], "r", joinType == GaussDBJoin.JoinType.FULL
                ? GaussDBBinaryOperation.Operator.EQUALS : GaussDBBinaryOperation.Operator.getRandomComparison());
        GaussDBExpression condition = comparison(columns[0], "l", columns[1], "r",
                GaussDBBinaryOperation.Operator.getRandomComparison());
        GaussDBSelect rightSelect = selectColumnFrom(rightTable, columns[1], "i", columns[1].getName());

        String originalQuery = GaussDBToStringVisitor
                .asString(selectStarJoinDerivedTable(leftTable, rightSelect, joinType, predicate));
        String transformedQuery = GaussDBToStringVisitor.asString(selectStarJoinDerivedTable(leftTable, rightSelect,
                joinType, transformJoinPredicate(joinType, predicate, condition)));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkThreeTableJoin() throws SQLException {
        GaussDBTable leftTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBTable middleTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBTable rightTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBColumn[] leftColumns = getSameTypeColumnPair(leftTable, middleTable);
        GaussDBColumn[] rightColumns = getSameTypeColumnPair(middleTable, rightTable);
        if (leftColumns == null || rightColumns == null) {
            return;
        }

        GaussDBExpression leftPredicate = comparison(leftColumns[0], "l", leftColumns[1], "m",
                GaussDBBinaryOperation.Operator.getRandomComparison());
        GaussDBExpression rightPredicate = comparison(rightColumns[0], "m", rightColumns[1], "r",
                GaussDBBinaryOperation.Operator.getRandomComparison());
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(middleTable.getColumns());
        generator.setTableAlias("m");
        GaussDBExpression leftCondition = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression rightCondition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        boolean transformLeft = Randomly.getBoolean();
        boolean transformRight = Randomly.getBoolean();
        if (!transformLeft && !transformRight) {
            transformLeft = true;
        }

        GaussDBExpression transformedLeftPredicate = transformLeft ? transform(leftPredicate, leftCondition)
                : leftPredicate;
        GaussDBExpression transformedRightPredicate = transformRight
                ? transform(rightPredicate, rightCondition) : rightPredicate;

        GaussDBSelect originalSelect = selectStarThreeTableJoin(leftTable, middleTable, rightTable, leftPredicate,
                rightPredicate);
        GaussDBSelect transformedSelect = selectStarThreeTableJoin(leftTable, middleTable, rightTable,
                transformedLeftPredicate, transformedRightPredicate);

        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);
        String transformedQuery = GaussDBToStringVisitor.asString(transformedSelect);
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkMixedThreeTableJoin() throws SQLException {
        GaussDBTable leftTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBTable middleTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBTable rightTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBColumn[] leftColumns = getSameTypeColumnPair(leftTable, middleTable);
        GaussDBColumn[] rightColumns = getSameTypeColumnPair(middleTable, rightTable);
        if (leftColumns == null || rightColumns == null) {
            return;
        }

        GaussDBJoin.JoinType leftJoinType = Randomly.fromOptions(GaussDBJoin.JoinType.INNER, GaussDBJoin.JoinType.LEFT,
                GaussDBJoin.JoinType.RIGHT, GaussDBJoin.JoinType.FULL);
        GaussDBJoin.JoinType rightJoinType = Randomly.fromOptions(GaussDBJoin.JoinType.INNER, GaussDBJoin.JoinType.LEFT,
                GaussDBJoin.JoinType.RIGHT, GaussDBJoin.JoinType.FULL);
        GaussDBExpression leftPredicate = comparison(leftColumns[0], "l", leftColumns[1], "m",
                leftJoinType == GaussDBJoin.JoinType.FULL ? GaussDBBinaryOperation.Operator.EQUALS
                        : GaussDBBinaryOperation.Operator.getRandomComparison());
        GaussDBExpression rightPredicate = comparison(rightColumns[0], "m", rightColumns[1], "r",
                rightJoinType == GaussDBJoin.JoinType.FULL ? GaussDBBinaryOperation.Operator.EQUALS
                        : GaussDBBinaryOperation.Operator.getRandomComparison());
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(middleTable.getColumns());
        generator.setTableAlias("m");
        GaussDBExpression leftCondition = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression rightCondition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        boolean transformLeft = Randomly.getBoolean();
        boolean transformRight = Randomly.getBoolean();
        if (!transformLeft && !transformRight) {
            transformLeft = true;
        }

        GaussDBExpression transformedLeftPredicate = transformLeft
                ? transformJoinPredicate(leftJoinType, leftPredicate, leftCondition) : leftPredicate;
        GaussDBExpression transformedRightPredicate = transformRight
                ? transformJoinPredicate(rightJoinType, rightPredicate, rightCondition) : rightPredicate;

        GaussDBSelect originalSelect = selectStarThreeTableJoin(leftTable, middleTable, rightTable, leftJoinType,
                rightJoinType, leftPredicate, rightPredicate);
        GaussDBSelect transformedSelect = selectStarThreeTableJoin(leftTable, middleTable, rightTable, leftJoinType,
                rightJoinType, transformedLeftPredicate, transformedRightPredicate);

        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);
        String transformedQuery = GaussDBToStringVisitor.asString(transformedSelect);
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkCte(GaussDBTable table, GaussDBExpressionGenerator generator) throws SQLException {
        GaussDBExpression predicate = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        String originalQuery = GaussDBToStringVisitor.asString(selectStarFromCte(selectStarWhere(table, predicate)));
        String transformedQuery = GaussDBToStringVisitor
                .asString(selectStarFromCte(selectStarWhere(table, transform(predicate, condition))));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkNestedCte(GaussDBTable table, GaussDBExpressionGenerator generator) throws SQLException {
        GaussDBExpression predicate = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        GaussDBSelect originalInnerSelect = selectStarWhere(table, predicate);
        GaussDBSelect originalInnerCte = selectStarFromCte(originalInnerSelect, "eet_inner_cte");
        GaussDBSelect originalOuterCte = selectStarFromCte(originalInnerCte, "eet_outer_cte");

        GaussDBSelect transformedInnerSelect = selectStarWhere(table, transform(predicate, condition));
        GaussDBSelect transformedInnerCte = selectStarFromCte(transformedInnerSelect, "eet_inner_cte");
        GaussDBSelect transformedOuterCte = selectStarFromCte(transformedInnerCte, "eet_outer_cte");

        String originalQuery = GaussDBToStringVisitor.asString(originalOuterCte);
        String transformedQuery = GaussDBToStringVisitor.asString(transformedOuterCte);
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkRecursiveCte() throws SQLException {
        GaussDBColumn valueColumn = new GaussDBColumn("value", GaussDBDataType.INT);
        GaussDBExpression predicate = new GaussDBBinaryOperation(new GaussDBColumnReference(valueColumn, "r"),
                GaussDBConstant.createIntConstant(3), GaussDBBinaryOperation.Operator.LESS, GaussDBDataType.BOOLEAN);

        String originalQuery = GaussDBToStringVisitor.asString(selectStarFromRecursiveCte(predicate));
        String transformedQuery = GaussDBToStringVisitor
                .asString(selectStarFromRecursiveCte(transform(predicate, GaussDBConstant.createFalse())));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkMultipleCtes(GaussDBTable table, GaussDBExpressionGenerator generator) throws SQLException {
        GaussDBExpression firstPredicate = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression secondPredicate = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression firstCondition = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression secondCondition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        GaussDBSelect originalSelect = selectStarFromMultipleCtes(table, firstPredicate, secondPredicate);
        GaussDBSelect transformedSelect = selectStarFromMultipleCtes(table,
                transform(firstPredicate, firstCondition),
                transform(secondPredicate, secondCondition));

        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);
        String transformedQuery = GaussDBToStringVisitor.asString(transformedSelect);
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkSubquery() throws SQLException {
        GaussDBTable outerTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBTable innerTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBColumn[] columns = getSameTypeColumnPair(outerTable, innerTable);
        if (columns == null) {
            return;
        }

        GaussDBSelect innerSelect = new GaussDBSelect();
        innerSelect.setFetchColumns(Collections.singletonList(GaussDBConstant.createIntConstant(1)));
        innerSelect
                .setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(innerTable), "i")));
        innerSelect.setWhereClause(
                comparison(columns[1], "i", columns[0], "o", GaussDBBinaryOperation.Operator.getRandomComparison()));

        GaussDBExpression exists = new GaussDBExists(new GaussDBSubquery(innerSelect), false);
        GaussDBExpressionGenerator outerGenerator = new GaussDBExpressionGenerator(state);
        outerGenerator.setColumns(outerTable.getColumns());
        outerGenerator.setTableAlias("o");
        GaussDBExpression condition = outerGenerator.generateExpression(GaussDBDataType.BOOLEAN);

        GaussDBSelect originalSelect = selectStarWhereExists(outerTable, exists);
        GaussDBSelect transformedSelect = selectStarWhereExists(outerTable, transform(exists, condition));

        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);
        String transformedQuery = GaussDBToStringVisitor.asString(transformedSelect);
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkInSubquery() throws SQLException {
        GaussDBTable outerTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBTable innerTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBColumn[] columns = getSameTypeColumnPair(outerTable, innerTable);
        if (columns == null) {
            return;
        }

        GaussDBSelect innerSelect = selectColumnFrom(innerTable, columns[1], "i");
        GaussDBExpression predicate = new GaussDBInSubquery(new GaussDBColumnReference(columns[0], "t"),
                new GaussDBSubquery(innerSelect), false);
        GaussDBExpressionGenerator outerGenerator = new GaussDBExpressionGenerator(state);
        outerGenerator.setColumns(outerTable.getColumns());
        outerGenerator.setTableAlias("t");
        GaussDBExpression condition = outerGenerator.generateExpression(GaussDBDataType.BOOLEAN);

        String originalQuery = GaussDBToStringVisitor.asString(selectStarWhere(outerTable, predicate));
        String transformedQuery = GaussDBToStringVisitor
                .asString(selectStarWhere(outerTable, transform(predicate, condition)));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkAnyAllSubquery() throws SQLException {
        GaussDBTable outerTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBTable innerTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBColumn[] columns = getSameTypeColumnPair(outerTable, innerTable);
        if (columns == null) {
            return;
        }

        GaussDBSelect innerSelect = selectColumnFrom(innerTable, columns[1], "i");
        GaussDBAnyAllOperation.Quantifier quantifier = GaussDBAnyAllOperation.Quantifier.getRandom();
        GaussDBExpression predicate = new GaussDBAnyAllOperation(new GaussDBColumnReference(columns[0], "t"),
                new GaussDBSubquery(innerSelect), GaussDBBinaryOperation.Operator.getRandomComparison(), quantifier);
        GaussDBExpressionGenerator outerGenerator = new GaussDBExpressionGenerator(state);
        outerGenerator.setColumns(outerTable.getColumns());
        outerGenerator.setTableAlias("t");
        GaussDBExpression condition = outerGenerator.generateExpression(GaussDBDataType.BOOLEAN);

        String originalQuery = GaussDBToStringVisitor.asString(selectStarWhere(outerTable, predicate));
        String transformedQuery = GaussDBToStringVisitor
                .asString(selectStarWhere(outerTable, transform(predicate, condition)));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkDerivedTable(GaussDBTable table, GaussDBExpressionGenerator generator) throws SQLException {
        GaussDBExpression predicate = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        String originalQuery = GaussDBToStringVisitor
                .asString(selectStarFromDerivedTable(selectStarWhere(table, predicate)));
        String transformedQuery = GaussDBToStringVisitor.asString(
                selectStarFromDerivedTable(selectStarWhere(table, transform(predicate, condition))));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkNestedDerivedTable(GaussDBTable table, GaussDBExpressionGenerator generator) throws SQLException {
        GaussDBExpression predicate = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        GaussDBSelect originalInnerSelect = selectStarWhere(table, predicate);
        GaussDBSelect originalMiddleSelect = selectStarFromDerivedTable(originalInnerSelect, "eet_inner_derived");
        GaussDBSelect originalOuterSelect = selectStarFromDerivedTable(originalMiddleSelect, "eet_outer_derived");

        GaussDBSelect transformedInnerSelect = selectStarWhere(table, transform(predicate, condition));
        GaussDBSelect transformedMiddleSelect = selectStarFromDerivedTable(transformedInnerSelect, "eet_inner_derived");
        GaussDBSelect transformedOuterSelect = selectStarFromDerivedTable(transformedMiddleSelect, "eet_outer_derived");

        String originalQuery = GaussDBToStringVisitor.asString(originalOuterSelect);
        String transformedQuery = GaussDBToStringVisitor.asString(transformedOuterSelect);
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkNotExists() throws SQLException {
        GaussDBTable outerTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBTable innerTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBColumn[] columns = getSameTypeColumnPair(outerTable, innerTable);
        if (columns == null) {
            return;
        }

        GaussDBSelect innerSelect = new GaussDBSelect();
        innerSelect.setFetchColumns(Collections.singletonList(GaussDBConstant.createIntConstant(1)));
        innerSelect
                .setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(innerTable), "i")));
        innerSelect.setWhereClause(
                comparison(columns[1], "i", columns[0], "o", GaussDBBinaryOperation.Operator.getRandomComparison()));

        GaussDBExpression exists = new GaussDBExists(new GaussDBSubquery(innerSelect), true);
        GaussDBExpressionGenerator outerGenerator = new GaussDBExpressionGenerator(state);
        outerGenerator.setColumns(outerTable.getColumns());
        outerGenerator.setTableAlias("o");
        GaussDBExpression condition = outerGenerator.generateExpression(GaussDBDataType.BOOLEAN);

        String originalQuery = GaussDBToStringVisitor.asString(selectStarWhereExists(outerTable, exists));
        String transformedQuery = GaussDBToStringVisitor
                .asString(selectStarWhereExists(outerTable, transform(exists, condition)));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkNotInSubquery() throws SQLException {
        GaussDBTable outerTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBTable innerTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBColumn[] columns = getSameTypeColumnPair(outerTable, innerTable);
        if (columns == null) {
            return;
        }

        GaussDBSelect innerSelect = selectColumnFrom(innerTable, columns[1], "i");
        GaussDBExpression predicate = new GaussDBInSubquery(new GaussDBColumnReference(columns[0], "t"),
                new GaussDBSubquery(innerSelect), true);
        GaussDBExpressionGenerator outerGenerator = new GaussDBExpressionGenerator(state);
        outerGenerator.setColumns(outerTable.getColumns());
        outerGenerator.setTableAlias("t");
        GaussDBExpression condition = outerGenerator.generateExpression(GaussDBDataType.BOOLEAN);

        String originalQuery = GaussDBToStringVisitor.asString(selectStarWhere(outerTable, predicate));
        String transformedQuery = GaussDBToStringVisitor
                .asString(selectStarWhere(outerTable, transform(predicate, condition)));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkScalarSubquery() throws SQLException {
        GaussDBTable outerTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBTable innerTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBColumn[] columns = getSameTypeColumnPair(outerTable, innerTable);
        if (columns == null) {
            return;
        }

        GaussDBSelect innerSelect = selectColumnFrom(innerTable, columns[1], "i");
        innerSelect.setOrderByClauses(Collections.singletonList(new GaussDBColumnReference(columns[1], "i")));
        innerSelect.setLimitClause(GaussDBConstant.createIntConstant(1));

        GaussDBExpression predicate = new GaussDBBinaryOperation(new GaussDBColumnReference(columns[0], "t"),
                new GaussDBSubquery(innerSelect), GaussDBBinaryOperation.Operator.getRandomComparison(),
                GaussDBDataType.BOOLEAN);
        GaussDBExpressionGenerator outerGenerator = new GaussDBExpressionGenerator(state);
        outerGenerator.setColumns(outerTable.getColumns());
        outerGenerator.setTableAlias("t");
        GaussDBExpression condition = outerGenerator.generateExpression(GaussDBDataType.BOOLEAN);

        String originalQuery = GaussDBToStringVisitor.asString(selectStarWhere(outerTable, predicate));
        String transformedQuery = GaussDBToStringVisitor
                .asString(selectStarWhere(outerTable, transform(predicate, condition)));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkDistinctSelectList(GaussDBTable table, GaussDBExpressionGenerator generator) throws SQLException {
        GaussDBDataType type = GaussDBDataType.getRandomType();
        GaussDBExpression expression = generator.generateExpression(type);
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        GaussDBSelect originalSelect = selectValueFrom(table, expression);
        originalSelect.setDistinct(true);
        GaussDBSelect transformedSelect = selectValueFrom(table, transform(expression, condition));
        transformedSelect.setDistinct(true);

        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);
        String transformedQuery = GaussDBToStringVisitor.asString(transformedSelect);
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkGroupByExpression(GaussDBTable table, GaussDBExpressionGenerator generator) throws SQLException {
        GaussDBDataType type = GaussDBDataType.getRandomType();
        GaussDBExpression expression = generator.generateExpression(type);
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        GaussDBSelect originalSelect = selectGroupByExpression(table, expression);
        GaussDBSelect transformedSelect = selectGroupByExpression(table, transform(expression, condition));

        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);
        String transformedQuery = GaussDBToStringVisitor.asString(transformedSelect);
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkMultiColumnGroupBy(GaussDBTable table, GaussDBExpressionGenerator generator) throws SQLException {
        GaussDBColumn firstColumn = Randomly.fromList(table.getColumns());
        GaussDBColumn secondColumn = Randomly.fromList(table.getColumns());
        GaussDBExpression firstExpression = new GaussDBColumnReference(firstColumn, TABLE_ALIAS);
        GaussDBExpression secondExpression = new GaussDBColumnReference(secondColumn, TABLE_ALIAS);
        GaussDBExpression firstCondition = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression secondCondition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        boolean transformFirst = Randomly.getBoolean();
        boolean transformSecond = Randomly.getBoolean();
        if (!transformFirst && !transformSecond) {
            transformFirst = true;
        }

        GaussDBExpression transformedFirst = transformFirst ? transform(firstExpression, firstCondition)
                : firstExpression;
        GaussDBExpression transformedSecond = transformSecond ? transform(secondExpression, secondCondition)
                : secondExpression;

        GaussDBSelect originalSelect = selectGroupByExpressions(table,
                Arrays.asList(firstExpression, secondExpression));
        GaussDBSelect transformedSelect = selectGroupByExpressions(table,
                Arrays.asList(transformedFirst, transformedSecond));

        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);
        String transformedQuery = GaussDBToStringVisitor.asString(transformedSelect);
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkCountAggregate(GaussDBTable table) throws SQLException {
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(table.getColumns());
        generator.setTableAlias(TABLE_ALIAS);
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        GaussDBAggregate originalCount = new GaussDBAggregate(GaussDBAggregate.Function.COUNT,
                Collections.singletonList(new GaussDBStar()), GaussDBDataType.INT);
        GaussDBAggregate transformedCount = new GaussDBAggregate(GaussDBAggregate.Function.COUNT,
                Collections.singletonList(transform(GaussDBConstant.createIntConstant(1), condition)),
                GaussDBDataType.INT);

        GaussDBSelect originalSelect = selectAggregateFrom(table, originalCount);
        GaussDBSelect transformedSelect = selectAggregateFrom(table, transformedCount);

        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);
        String transformedQuery = GaussDBToStringVisitor.asString(transformedSelect);
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkWindowFunction(GaussDBTable table, GaussDBExpressionGenerator generator) throws SQLException {
        GaussDBColumn partitionColumn = Randomly.fromList(table.getColumns());
        GaussDBColumn orderColumn = Randomly.fromList(table.getColumns());
        GaussDBExpression partitionExpression = new GaussDBColumnReference(partitionColumn, TABLE_ALIAS);
        GaussDBExpression orderExpression = new GaussDBColumnReference(orderColumn, TABLE_ALIAS);
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);
        String functionName = Randomly.fromOptions("row_number", "rank", "dense_rank", "percent_rank", "cume_dist");
        GaussDBDataType returnType = functionName.equals("percent_rank") || functionName.equals("cume_dist")
                ? GaussDBDataType.FLOAT : GaussDBDataType.INT;

        GaussDBWindowFunction originalFunction = new GaussDBWindowFunction(functionName, Collections.emptyList(),
                Collections.singletonList(partitionExpression), Collections.singletonList(orderExpression), returnType);
        GaussDBWindowFunction transformedFunction = new GaussDBWindowFunction(functionName, Collections.emptyList(),
                Collections.singletonList(transform(partitionExpression, condition)),
                Collections.singletonList(orderExpression), returnType);

        String originalQuery = GaussDBToStringVisitor.asString(selectValueFrom(table, originalFunction));
        String transformedQuery = GaussDBToStringVisitor.asString(selectValueFrom(table, transformedFunction));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkLagLeadWindowFunction(GaussDBTable table, GaussDBExpressionGenerator generator)
            throws SQLException {
        GaussDBColumn argumentColumn = Randomly.fromList(table.getColumns());
        GaussDBColumn partitionColumn = Randomly.fromList(table.getColumns());
        GaussDBColumn orderColumn = Randomly.fromList(table.getColumns());
        GaussDBExpression argument = new GaussDBColumnReference(argumentColumn, TABLE_ALIAS);
        GaussDBExpression partitionExpression = new GaussDBColumnReference(partitionColumn, TABLE_ALIAS);
        GaussDBExpression orderExpression = new GaussDBColumnReference(orderColumn, TABLE_ALIAS);
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);
        String functionName = Randomly.fromOptions("lag", "lead");

        GaussDBWindowFunction originalFunction = new GaussDBWindowFunction(functionName,
                Collections.singletonList(argument), Collections.singletonList(partitionExpression),
                Collections.singletonList(orderExpression), argumentColumn.getType());
        GaussDBWindowFunction transformedFunction = new GaussDBWindowFunction(functionName,
                Collections.singletonList(argument),
                Collections.singletonList(transform(partitionExpression, condition)),
                Collections.singletonList(orderExpression), argumentColumn.getType());

        String originalQuery = GaussDBToStringVisitor.asString(selectValueFrom(table, originalFunction));
        String transformedQuery = GaussDBToStringVisitor.asString(selectValueFrom(table, transformedFunction));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkValueWindowFunction(GaussDBTable table, GaussDBExpressionGenerator generator)
            throws SQLException {
        GaussDBColumn argumentColumn = Randomly.fromList(table.getColumns());
        GaussDBColumn partitionColumn = Randomly.fromList(table.getColumns());
        GaussDBColumn orderColumn = Randomly.fromList(table.getColumns());
        GaussDBExpression partitionExpression = new GaussDBColumnReference(partitionColumn, TABLE_ALIAS);
        GaussDBExpression orderExpression = new GaussDBColumnReference(orderColumn, TABLE_ALIAS);
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);
        String functionName = Randomly.fromOptions("first_value", "last_value", "ntile", "nth_value");
        List<GaussDBExpression> arguments;
        GaussDBDataType returnType;

        if (functionName.equals("ntile")) {
            arguments = Collections.singletonList(GaussDBConstant.createIntConstant(Randomly.fromOptions(1, 2, 3, 4)));
            returnType = GaussDBDataType.INT;
        } else if (functionName.equals("nth_value")) {
            arguments = Arrays.asList(new GaussDBColumnReference(argumentColumn, TABLE_ALIAS),
                    GaussDBConstant.createIntConstant(Randomly.fromOptions(1, 2, 3, 4)));
            returnType = argumentColumn.getType();
        } else {
            arguments = Collections.singletonList(new GaussDBColumnReference(argumentColumn, TABLE_ALIAS));
            returnType = argumentColumn.getType();
        }

        GaussDBWindowFunction originalFunction = new GaussDBWindowFunction(functionName, arguments,
                Collections.singletonList(partitionExpression), Collections.singletonList(orderExpression), returnType);
        GaussDBWindowFunction transformedFunction = new GaussDBWindowFunction(functionName, arguments,
                Collections.singletonList(transform(partitionExpression, condition)),
                Collections.singletonList(orderExpression), returnType);

        String originalQuery = GaussDBToStringVisitor.asString(selectValueFrom(table, originalFunction));
        String transformedQuery = GaussDBToStringVisitor.asString(selectValueFrom(table, transformedFunction));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkFramedWindowFunction(GaussDBTable table, GaussDBExpressionGenerator generator)
            throws SQLException {
        GaussDBColumn argumentColumn = Randomly.fromList(table.getColumns());
        GaussDBColumn partitionColumn = Randomly.fromList(table.getColumns());
        GaussDBColumn orderColumn = Randomly.fromList(table.getColumns());
        GaussDBExpression argument = new GaussDBColumnReference(argumentColumn, TABLE_ALIAS);
        GaussDBExpression partitionExpression = new GaussDBColumnReference(partitionColumn, TABLE_ALIAS);
        GaussDBExpression orderExpression = new GaussDBColumnReference(orderColumn, TABLE_ALIAS);
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);
        String functionName = Randomly.fromOptions("first_value", "last_value");
        GaussDBWindowFrame frame = Randomly.fromOptions(
                GaussDBWindowFrame.between(GaussDBWindowFrame.FrameType.ROWS,
                        GaussDBWindowFrame.Boundary.unboundedPreceding(), GaussDBWindowFrame.Boundary.currentRow()),
                GaussDBWindowFrame.between(GaussDBWindowFrame.FrameType.ROWS,
                        GaussDBWindowFrame.Boundary.preceding(GaussDBConstant.createIntConstant(1)),
                        GaussDBWindowFrame.Boundary.currentRow()),
                GaussDBWindowFrame.between(GaussDBWindowFrame.FrameType.RANGE,
                        GaussDBWindowFrame.Boundary.unboundedPreceding(), GaussDBWindowFrame.Boundary.currentRow()));

        GaussDBWindowFunction originalFunction = new GaussDBWindowFunction(functionName,
                Collections.singletonList(argument), Collections.singletonList(partitionExpression),
                Collections.singletonList(orderExpression), argumentColumn.getType(), frame);
        GaussDBWindowFunction transformedFunction = new GaussDBWindowFunction(functionName,
                Collections.singletonList(argument),
                Collections.singletonList(transform(partitionExpression, condition)),
                Collections.singletonList(orderExpression), argumentColumn.getType(), frame);

        String originalQuery = GaussDBToStringVisitor.asString(selectValueFrom(table, originalFunction));
        String transformedQuery = GaussDBToStringVisitor.asString(selectValueFrom(table, transformedFunction));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkFilteredWindowFunction(GaussDBTable table, GaussDBExpressionGenerator generator)
            throws SQLException {
        GaussDBColumn partitionColumn = Randomly.fromList(table.getColumns());
        GaussDBColumn orderColumn = Randomly.fromList(table.getColumns());
        GaussDBExpression partitionExpression = new GaussDBColumnReference(partitionColumn, TABLE_ALIAS);
        GaussDBExpression orderExpression = new GaussDBColumnReference(orderColumn, TABLE_ALIAS);
        GaussDBExpression predicate = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        GaussDBWindowFunction originalFunction = new GaussDBWindowFunction("count",
                Collections.singletonList(new GaussDBStar()), Collections.singletonList(partitionExpression),
                Collections.singletonList(orderExpression), GaussDBDataType.INT, null, predicate);
        GaussDBWindowFunction transformedFunction = new GaussDBWindowFunction("count",
                Collections.singletonList(new GaussDBStar()), Collections.singletonList(partitionExpression),
                Collections.singletonList(orderExpression), GaussDBDataType.INT, null,
                transform(predicate, condition));

        String originalQuery = GaussDBToStringVisitor.asString(selectValueFrom(table, originalFunction));
        String transformedQuery = GaussDBToStringVisitor.asString(selectValueFrom(table, transformedFunction));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkOrderByExpression(GaussDBTable table, GaussDBExpressionGenerator generator) throws SQLException {
        GaussDBColumn column = Randomly.fromList(table.getColumns());
        GaussDBExpression baseExpr = new GaussDBColumnReference(column, TABLE_ALIAS);
        GaussDBExpression expression = Randomly.getBoolean() ? generator.generateOrderByTerm(baseExpr) : baseExpr;
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression tableoid = tableoidReference(TABLE_ALIAS);
        GaussDBExpression ctid = ctidReference(TABLE_ALIAS);

        GaussDBSelect originalSelect = selectStarWhere(table, GaussDBConstant.createBooleanConstant(true));
        originalSelect.setOrderByClauses(Arrays.asList(expression, tableoid, ctid));

        GaussDBSelect transformedSelect = selectStarWhere(table, GaussDBConstant.createBooleanConstant(true));
        transformedSelect.setOrderByClauses(Arrays.asList(transform(expression, condition), tableoid, ctid));

        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);
        String transformedQuery = GaussDBToStringVisitor.asString(transformedSelect);
        checkEquivalentOrderedResults(originalQuery, transformedQuery);
    }

    private void checkMultiColumnOrderBy(GaussDBTable table, GaussDBExpressionGenerator generator) throws SQLException {
        GaussDBColumn firstColumn = Randomly.fromList(table.getColumns());
        GaussDBColumn secondColumn = Randomly.fromList(table.getColumns());
        GaussDBExpression firstExpression = new GaussDBColumnReference(firstColumn, TABLE_ALIAS);
        GaussDBExpression secondExpression = new GaussDBColumnReference(secondColumn, TABLE_ALIAS);
        GaussDBExpression firstCondition = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression secondCondition = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression tableoid = tableoidReference(TABLE_ALIAS);
        GaussDBExpression ctid = ctidReference(TABLE_ALIAS);

        boolean transformFirst = Randomly.getBoolean();
        boolean transformSecond = Randomly.getBoolean();
        if (!transformFirst && !transformSecond) {
            transformFirst = true;
        }

        GaussDBExpression transformedFirst = transformFirst ? transform(firstExpression, firstCondition)
                : firstExpression;
        GaussDBExpression transformedSecond = transformSecond ? transform(secondExpression, secondCondition)
                : secondExpression;

        GaussDBSelect originalSelect = selectStarWhere(table, GaussDBConstant.createBooleanConstant(true));
        originalSelect.setOrderByClauses(Arrays.asList(firstExpression, secondExpression, tableoid, ctid));

        GaussDBSelect transformedSelect = selectStarWhere(table, GaussDBConstant.createBooleanConstant(true));
        transformedSelect.setOrderByClauses(Arrays.asList(transformedFirst, transformedSecond, tableoid, ctid));

        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);
        String transformedQuery = GaussDBToStringVisitor.asString(transformedSelect);
        checkEquivalentOrderedResults(originalQuery, transformedQuery);
    }

    private void checkSetOperation(GaussDBTable table, GaussDBExpressionGenerator generator) throws SQLException {
        GaussDBExpression leftPredicate = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression rightPredicate = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression leftCondition = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpression rightCondition = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBSetOperation.Operator operator = GaussDBSetOperation.Operator.getRandom();

        boolean transformLeft = Randomly.getBoolean();
        boolean transformRight = Randomly.getBoolean();
        if (!transformLeft && !transformRight) {
            transformLeft = true;
        }

        GaussDBSelect originalLeft = selectStarWhere(table, leftPredicate);
        GaussDBSelect originalRight = selectStarWhere(table, rightPredicate);
        GaussDBSetOperation originalSetOperation = new GaussDBSetOperation(originalLeft, originalRight, operator);

        GaussDBSelect transformedLeft = transformLeft
                ? selectStarWhere(table, transform(leftPredicate, leftCondition)) : originalLeft;
        GaussDBSelect transformedRight = transformRight
                ? selectStarWhere(table, transform(rightPredicate, rightCondition)) : originalRight;
        GaussDBSetOperation transformedSetOperation = new GaussDBSetOperation(transformedLeft, transformedRight,
                operator);

        String originalQuery = GaussDBToStringVisitor.asString(originalSetOperation);
        String transformedQuery = GaussDBToStringVisitor.asString(transformedSetOperation);
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkLimitExpression(GaussDBTable table) throws SQLException {
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);
        GaussDBExpression limit = GaussDBConstant.createIntConstant(1);
        GaussDBExpression tableoid = tableoidReference(TABLE_ALIAS);
        GaussDBExpression ctid = ctidReference(TABLE_ALIAS);

        GaussDBSelect originalSelect = selectStarWhere(table, GaussDBConstant.createBooleanConstant(true));
        originalSelect.setOrderByClauses(Arrays.asList(tableoid, ctid));
        originalSelect.setLimitClause(limit);

        GaussDBSelect transformedSelect = selectStarWhere(table, GaussDBConstant.createBooleanConstant(true));
        transformedSelect.setOrderByClauses(Arrays.asList(tableoid, ctid));
        transformedSelect.setLimitClause(equivalentExpression(limit, condition));

        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);
        String transformedQuery = GaussDBToStringVisitor.asString(transformedSelect);
        checkEquivalentOrderedResults(originalQuery, transformedQuery);
    }

    private void checkOffsetExpression(GaussDBTable table) throws SQLException {
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);
        GaussDBExpression offset = GaussDBConstant.createIntConstant(1);
        GaussDBExpression tableoid = tableoidReference(TABLE_ALIAS);
        GaussDBExpression ctid = ctidReference(TABLE_ALIAS);

        GaussDBSelect originalSelect = selectStarWhere(table, GaussDBConstant.createBooleanConstant(true));
        originalSelect.setOrderByClauses(Arrays.asList(tableoid, ctid));
        originalSelect.setLimitClause(GaussDBConstant.createIntConstant(1000));
        originalSelect.setOffsetClause(offset);

        GaussDBSelect transformedSelect = selectStarWhere(table, GaussDBConstant.createBooleanConstant(true));
        transformedSelect.setOrderByClauses(Arrays.asList(tableoid, ctid));
        transformedSelect.setLimitClause(GaussDBConstant.createIntConstant(1000));
        transformedSelect.setOffsetClause(equivalentExpression(offset, condition));

        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);
        String transformedQuery = GaussDBToStringVisitor.asString(transformedSelect);
        checkEquivalentOrderedResults(originalQuery, transformedQuery);
    }

    private void checkSelectListScalarSubquery(GaussDBTable table, GaussDBExpressionGenerator generator)
            throws SQLException {
        GaussDBColumn column = Randomly.fromList(table.getColumns());
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        GaussDBSelect innerSelect = new GaussDBSelect();
        innerSelect.setFetchColumns(
                Collections.singletonList(new GaussDBAlias(new GaussDBColumnReference(column, "i"), "value")));
        innerSelect.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), "i")));
        innerSelect.setWhereClause(new GaussDBBinaryOperation(new GaussDBColumnReference(column, "i"),
                new GaussDBColumnReference(column, TABLE_ALIAS), GaussDBBinaryOperation.Operator.EQUALS,
                GaussDBDataType.BOOLEAN));
        innerSelect.setOrderByClauses(Arrays.asList(tableoidReference("i"), ctidReference("i")));
        innerSelect.setLimitClause(GaussDBConstant.createIntConstant(1));

        GaussDBExpression subquery = new GaussDBSubquery(innerSelect);
        GaussDBExpression transformed = new GaussDBCaseOperation(Collections.singletonList(condition),
                Collections.singletonList(subquery), subquery, column.getType());

        String originalQuery = GaussDBToStringVisitor.asString(selectValueFrom(table, subquery));
        String transformedQuery = GaussDBToStringVisitor.asString(selectValueFrom(table, transformed));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkSelectListExists(GaussDBTable table, GaussDBExpressionGenerator generator) throws SQLException {
        GaussDBColumn column = Randomly.fromList(table.getColumns());
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);

        GaussDBSelect innerSelect = new GaussDBSelect();
        innerSelect.setFetchColumns(Collections.singletonList(GaussDBConstant.createIntConstant(1)));
        innerSelect.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), "i")));
        innerSelect.setWhereClause(new GaussDBBinaryOperation(new GaussDBColumnReference(column, "i"),
                new GaussDBColumnReference(column, TABLE_ALIAS), GaussDBBinaryOperation.Operator.EQUALS,
                GaussDBDataType.BOOLEAN));

        GaussDBExpression exists = new GaussDBExists(new GaussDBSubquery(innerSelect), false);
        GaussDBExpression transformed = transform(exists, condition);

        String originalQuery = GaussDBToStringVisitor.asString(selectValueFrom(table, exists));
        String transformedQuery = GaussDBToStringVisitor.asString(selectValueFrom(table, transformed));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkAggregateArgument(GaussDBTable table, GaussDBExpressionGenerator generator) throws SQLException {
        List<GaussDBColumn> numericColumns = table.getColumns().stream()
                .filter(column -> column.getType() == GaussDBDataType.INT || column.getType() == GaussDBDataType.DECIMAL
                        || column.getType() == GaussDBDataType.FLOAT || column.getType() == GaussDBDataType.REAL)
                .collect(Collectors.toList());
        if (numericColumns.isEmpty()) {
            return;
        }

        GaussDBColumn column = Randomly.fromList(numericColumns);
        GaussDBExpression argument = new GaussDBColumnReference(column, TABLE_ALIAS);
        GaussDBExpression condition = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBAggregate.Function function = Randomly.fromOptions(GaussDBAggregate.Function.SUM,
                GaussDBAggregate.Function.MIN, GaussDBAggregate.Function.MAX, GaussDBAggregate.Function.AVG);

        GaussDBAggregate originalAggregate = new GaussDBAggregate(function, Collections.singletonList(argument),
                column.getType());
        GaussDBAggregate transformedAggregate = new GaussDBAggregate(function,
                Collections.singletonList(transform(argument, condition)), column.getType());

        String originalQuery = GaussDBToStringVisitor.asString(selectAggregateFrom(table, originalAggregate));
        String transformedQuery = GaussDBToStringVisitor.asString(selectAggregateFrom(table, transformedAggregate));
        checkEquivalentResults(originalQuery, transformedQuery);
    }

    private void checkEquivalentResults(String originalQuery, String transformedQuery) throws SQLException {
        lastOriginalQuery = originalQuery;
        SQLConnection connection = state.getConnection();
        try (Statement statement = connection.createStatement()) {
            List<String> originalResults = executeAndGetRows(statement, originalQuery);
            List<String> transformedResults = executeAndGetRows(statement, transformedQuery);
            if (areEquivalent(originalResults, transformedResults)) {
                return;
            }

            // Re-run both queries to avoid reporting nondeterministic results.
            originalResults = executeAndGetRows(statement, originalQuery);
            transformedResults = executeAndGetRows(statement, transformedQuery);
            assertEquivalent(originalQuery, transformedQuery, originalResults, transformedResults);
        }
    }

    private void checkEquivalentOrderedResults(String originalQuery, String transformedQuery) throws SQLException {
        lastOriginalQuery = originalQuery;
        SQLConnection connection = state.getConnection();
        try (Statement statement = connection.createStatement()) {
            List<String> originalResults = executeAndGetRows(statement, originalQuery);
            List<String> transformedResults = executeAndGetRows(statement, transformedQuery);
            if (originalResults.equals(transformedResults)) {
                return;
            }

            originalResults = executeAndGetRows(statement, originalQuery);
            transformedResults = executeAndGetRows(statement, transformedQuery);
            if (originalResults.equals(transformedResults)) {
                return;
            }
            reproducer = new GaussDBEETReproducer(originalQuery, transformedQuery, true);
            throw new AssertionError(
                    "EET ordered-result mismatch\nOriginal: " + originalQuery + "\nTransformed: " + transformedQuery
                            + "\nOriginalResult: " + originalResults + "\nTransformedResult: " + transformedResults);
        }
    }

    private GaussDBExpression transform(GaussDBExpression expression, GaussDBExpression condition) {
        return GaussDBEETTransformer.transform(expression, condition, state.getRandomly());
    }

    static GaussDBCaseOperation equivalentExpression(GaussDBExpression expression, GaussDBExpression condition) {
        return new GaussDBCaseOperation(Collections.singletonList(condition), Collections.singletonList(expression),
                expression, expression.getExpressionType());
    }

    static GaussDBExpression equivalentFullJoinPredicate(GaussDBExpression predicate, GaussDBExpression condition) {
        GaussDBExpression conditionIsNotNull = new GaussDBUnaryOperation(condition,
                GaussDBUnaryOperation.Operator.IS_NOT_NULL, GaussDBDataType.BOOLEAN);
        GaussDBExpression conditionIsNull = new GaussDBUnaryOperation(condition, GaussDBUnaryOperation.Operator.IS_NULL,
                GaussDBDataType.BOOLEAN);
        GaussDBExpression tautology = new GaussDBBinaryOperation(conditionIsNotNull, conditionIsNull,
                GaussDBBinaryOperation.Operator.OR, GaussDBDataType.BOOLEAN);
        return new GaussDBBinaryOperation(predicate, tautology, GaussDBBinaryOperation.Operator.AND,
                GaussDBDataType.BOOLEAN);
    }

    private GaussDBExpression transformJoinPredicate(GaussDBJoin.JoinType joinType, GaussDBExpression predicate,
            GaussDBExpression condition) {
        return joinType == GaussDBJoin.JoinType.FULL ? equivalentFullJoinPredicate(predicate, condition)
                : transform(predicate, condition);
    }

    private static GaussDBColumnReference tableoidReference(String alias) {
        return new GaussDBColumnReference(new GaussDBColumn("tableoid", GaussDBDataType.INT), alias);
    }

    private static GaussDBColumnReference ctidReference(String alias) {
        return new GaussDBColumnReference(new GaussDBColumn("ctid", GaussDBDataType.TEXT), alias);
    }

    static GaussDBSelect selectValueFrom(GaussDBTable table, GaussDBExpression expression) {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(new GaussDBAlias(expression, "value")));
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS)));
        return select;
    }

    static GaussDBSelect selectColumnFrom(GaussDBTable table, GaussDBColumn column, String alias) {
        return selectColumnFrom(table, column, alias, "value");
    }

    static GaussDBSelect selectColumnFrom(GaussDBTable table, GaussDBColumn column, String alias, String outputAlias) {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(
                Collections.singletonList(new GaussDBAlias(new GaussDBColumnReference(column, alias), outputAlias)));
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), alias)));
        return select;
    }

    static GaussDBSelect selectStarWhere(GaussDBTable table, GaussDBExpression predicate) {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS)));
        select.setWhereClause(predicate);
        return select;
    }

    static GaussDBSelect selectStarJoinDerivedTable(GaussDBTable leftTable, GaussDBSelect rightSelect,
            GaussDBJoin.JoinType joinType, GaussDBExpression predicate) {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(leftTable), "l")));
        select.setJoinClauses(
                Collections.singletonList(new GaussDBJoin(new GaussDBAlias(new GaussDBTableReference(leftTable), "l"),
                        new GaussDBAlias(new GaussDBExpressionBag(new GaussDBSubquery(rightSelect)), "r"), joinType,
                        predicate)));
        return select;
    }

    static GaussDBSelect selectGroupByHaving(GaussDBTable table, GaussDBColumn column, GaussDBExpression predicate) {
        GaussDBColumnReference groupByColumn = new GaussDBColumnReference(column, TABLE_ALIAS);
        GaussDBAggregate count = new GaussDBAggregate(GaussDBAggregate.Function.COUNT,
                Collections.singletonList(new GaussDBStar()), GaussDBDataType.INT);

        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(
                Arrays.asList(new GaussDBAlias(groupByColumn, "group_value"), new GaussDBAlias(count, "row_count")));
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS)));
        select.setGroupByExpressions(Collections.singletonList(groupByColumn));
        select.setHavingClause(predicate);
        return select;
    }

    static GaussDBSelect selectGroupByExpression(GaussDBTable table, GaussDBExpression expression) {
        GaussDBExpression groupByExpression = expression instanceof GaussDBConstant
                ? new GaussDBCastOperation(expression, expression.getExpressionType()) : expression;
        GaussDBAggregate count = new GaussDBAggregate(GaussDBAggregate.Function.COUNT,
                Collections.singletonList(new GaussDBStar()), GaussDBDataType.INT);

        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Arrays.asList(new GaussDBAlias(groupByExpression, "group_value"),
                new GaussDBAlias(count, "row_count")));
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS)));
        select.setGroupByExpressions(Collections.singletonList(groupByExpression));
        return select;
    }

    static GaussDBSelect selectGroupByExpressions(GaussDBTable table, List<GaussDBExpression> expressions) {
        List<GaussDBExpression> groupByExpressions = expressions.stream()
                .map(expression -> expression instanceof GaussDBConstant
                        ? new GaussDBCastOperation(expression, expression.getExpressionType()) : expression)
                .collect(Collectors.toList());
        GaussDBAggregate count = new GaussDBAggregate(GaussDBAggregate.Function.COUNT,
                Collections.singletonList(new GaussDBStar()), GaussDBDataType.INT);

        List<GaussDBExpression> fetchColumns = new ArrayList<>();
        for (int i = 0; i < groupByExpressions.size(); i++) {
            fetchColumns.add(new GaussDBAlias(groupByExpressions.get(i), "group_value" + i));
        }
        fetchColumns.add(new GaussDBAlias(count, "row_count"));

        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(fetchColumns);
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS)));
        select.setGroupByExpressions(groupByExpressions);
        return select;
    }

    static GaussDBSelect selectAggregateFrom(GaussDBTable table, GaussDBExpression aggregate) {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(new GaussDBAlias(aggregate, "row_count")));
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS)));
        return select;
    }

    static GaussDBSelect selectStarJoin(GaussDBTable leftTable, GaussDBTable rightTable, GaussDBJoin.JoinType joinType,
            GaussDBExpression predicate) {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(leftTable), "l")));
        select.setJoinClauses(
                Collections.singletonList(new GaussDBJoin(new GaussDBAlias(new GaussDBTableReference(leftTable), "l"),
                        new GaussDBAlias(new GaussDBTableReference(rightTable), "r"), joinType, predicate)));
        return select;
    }

    static GaussDBSelect selectStarThreeTableJoin(GaussDBTable leftTable, GaussDBTable middleTable,
            GaussDBTable rightTable, GaussDBExpression leftPredicate, GaussDBExpression rightPredicate) {
        return selectStarThreeTableJoin(leftTable, middleTable, rightTable, GaussDBJoin.JoinType.INNER,
                GaussDBJoin.JoinType.INNER, leftPredicate, rightPredicate);
    }

    static GaussDBSelect selectStarThreeTableJoin(GaussDBTable leftTable, GaussDBTable middleTable,
            GaussDBTable rightTable, GaussDBJoin.JoinType leftJoinType, GaussDBJoin.JoinType rightJoinType,
            GaussDBExpression leftPredicate, GaussDBExpression rightPredicate) {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(leftTable), "l")));
        select.setJoinClauses(Arrays.asList(
                new GaussDBJoin(new GaussDBAlias(new GaussDBTableReference(leftTable), "l"),
                        new GaussDBAlias(new GaussDBTableReference(middleTable), "m"), leftJoinType, leftPredicate),
                new GaussDBJoin(new GaussDBAlias(new GaussDBTableReference(middleTable), "m"),
                        new GaussDBAlias(new GaussDBTableReference(rightTable), "r"), rightJoinType, rightPredicate)));
        return select;
    }

    static GaussDBSelect selectStarFromCte(GaussDBSelect innerSelect) {
        return selectStarFromCte(innerSelect, "eet_cte");
    }

    static GaussDBSelect selectStarFromCte(GaussDBSelect innerSelect, String alias) {
        GaussDBTable cteTable = new GaussDBTable(alias, Collections.emptyList(), null, false, true);
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(cteTable), alias)));
        select.setWithClause(new GaussDBWithClause(alias, new GaussDBSubquery(innerSelect)));
        return select;
    }

    static GaussDBSelect selectStarFromCteResult(String alias) {
        GaussDBTable cteTable = new GaussDBTable(alias, Collections.emptyList(), null, false, true);
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(cteTable), alias)));
        return select;
    }

    static GaussDBSelect selectStarFromMultipleCtes(GaussDBTable table, GaussDBExpression firstPredicate,
            GaussDBExpression secondPredicate) {
        String firstAlias = "eet_cte0";
        String secondAlias = "eet_cte1";
        GaussDBWithClause firstClause = new GaussDBWithClause(firstAlias,
                new GaussDBSubquery(selectStarWhere(table, firstPredicate)));
        GaussDBWithClause secondClause = new GaussDBWithClause(secondAlias,
                new GaussDBSubquery(selectStarWhere(table, secondPredicate)));
        GaussDBSetOperation unionAll = new GaussDBSetOperation(selectStarFromCteResult(firstAlias),
                selectStarFromCteResult(secondAlias), GaussDBSetOperation.Operator.UNION_ALL);

        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        select.setFromList(Collections
                .singletonList(new GaussDBAlias(new GaussDBExpressionBag(new GaussDBSubquery(unionAll)), "q")));
        select.setWithClause(new GaussDBWithClauseGroup(Arrays.asList(firstClause, secondClause)));
        return select;
    }

    static GaussDBSelect selectStarFromRecursiveCte(GaussDBExpression recursivePredicate) {
        String alias = "eet_recursive_cte";
        GaussDBColumn valueColumn = new GaussDBColumn("value", GaussDBDataType.INT);
        GaussDBTable cteTable = new GaussDBTable(alias, Collections.singletonList(valueColumn), null, true, false);

        GaussDBSelect anchor = new GaussDBSelect();
        anchor.setFetchColumns(
                Collections.singletonList(new GaussDBAlias(GaussDBConstant.createIntConstant(1), "value")));
        anchor.setFromList(Collections.emptyList());

        GaussDBSelect recursive = new GaussDBSelect();
        recursive.setFetchColumns(Collections
                .singletonList(new GaussDBAlias(new GaussDBBinaryOperation(new GaussDBColumnReference(valueColumn, "r"),
                        GaussDBConstant.createIntConstant(1), GaussDBBinaryOperation.Operator.PLUS,
                        GaussDBDataType.INT), "value")));
        recursive.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(cteTable), "r")));
        recursive.setWhereClause(recursivePredicate);

        GaussDBSetOperation unionAll = new GaussDBSetOperation(anchor, recursive,
                GaussDBSetOperation.Operator.UNION_ALL);
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(cteTable), "o")));
        select.setWithClause(new GaussDBWithClause(alias, unionAll, true));
        return select;
    }

    static GaussDBSelect selectStarWhereExists(GaussDBTable table, GaussDBExpression predicate) {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), "o")));
        select.setWhereClause(predicate);
        return select;
    }

    static GaussDBSelect selectStarFromDerivedTable(GaussDBSelect innerSelect) {
        return selectStarFromDerivedTable(innerSelect, "eet_derived");
    }

    static GaussDBSelect selectStarFromDerivedTable(GaussDBSelect innerSelect, String alias) {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        select.setFromList(Collections
                .singletonList(new GaussDBAlias(new GaussDBExpressionBag(new GaussDBSubquery(innerSelect)), alias)));
        return select;
    }

    private static GaussDBColumn[] getSameTypeColumnPair(GaussDBTable leftTable, GaussDBTable rightTable) {
        List<GaussDBColumn[]> pairs = new ArrayList<>();
        for (GaussDBColumn leftColumn : leftTable.getColumns()) {
            for (GaussDBColumn rightColumn : rightTable.getColumns()) {
                if (leftColumn.getType() == rightColumn.getType()) {
                    pairs.add(new GaussDBColumn[] { leftColumn, rightColumn });
                }
            }
        }
        return pairs.isEmpty() ? null : Randomly.fromList(pairs);
    }

    private static GaussDBExpression comparison(GaussDBColumn leftColumn, String leftAlias, GaussDBColumn rightColumn,
            String rightAlias, GaussDBBinaryOperation.Operator operator) {
        return new GaussDBBinaryOperation(new GaussDBColumnReference(leftColumn, leftAlias),
                new GaussDBColumnReference(rightColumn, rightAlias), operator, GaussDBDataType.BOOLEAN);
    }

    private void assertEquivalent(String originalQuery, String transformedQuery, List<String> originalResults,
            List<String> transformedResults) {
        if (areEquivalent(originalResults, transformedResults)) {
            return;
        }
        reproducer = new GaussDBEETReproducer(originalQuery, transformedQuery);
        throw new AssertionError("EET mismatch\nOriginal: " + originalQuery + "\nTransformed: " + transformedQuery
                + "\nOriginalResult: " + originalResults + "\nTransformedResult: " + transformedResults);
    }

    private boolean areEquivalent(List<String> originalResults, List<String> transformedResults) {
        return sorted(originalResults).equals(sorted(transformedResults));
    }

    private List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
        return copy;
    }

    private List<String> executeAndGetRows(Statement statement, String query) throws SQLException {
        List<String> results = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery(query)) {
            int columnCount = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.add(normalizeCellValue(resultSet.getString(i)));
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

    static String normalizeCellValue(String value) {
        if (value != null && value.length() == 22 && value.endsWith(" AD") && value.charAt(4) == '-'
                && value.charAt(7) == '-') {
            return value.substring(0, value.length() - 3);
        }
        return value;
    }

    @Override
    public Reproducer<GaussDBGlobalState> getLastReproducer() {
        return reproducer;
    }

    @Override
    public String getLastQueryString() {
        return lastOriginalQuery;
    }

}
