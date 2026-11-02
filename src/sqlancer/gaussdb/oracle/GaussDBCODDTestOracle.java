package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
import sqlancer.gaussdb.ast.GaussDBFunction;
import sqlancer.gaussdb.ast.GaussDBInOperation;
import sqlancer.gaussdb.ast.GaussDBInSubquery;
import sqlancer.gaussdb.ast.GaussDBJoin;
import sqlancer.gaussdb.ast.GaussDBSelect;
import sqlancer.gaussdb.ast.GaussDBStar;
import sqlancer.gaussdb.ast.GaussDBSubquery;
import sqlancer.gaussdb.ast.GaussDBTableReference;
import sqlancer.gaussdb.ast.GaussDBValues;
import sqlancer.gaussdb.ast.GaussDBWithClause;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

public class GaussDBCODDTestOracle implements TestOracle<GaussDBGlobalState> {

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBCODDTestReproducer reproducer;
    private String lastOriginalQuery;

    public GaussDBCODDTestOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        switch (Randomly.fromOptions(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)) {
        case 0:
            checkInFolding();
            break;
        case 1:
            checkIndependentExpressionFolding();
            break;
        case 2:
            checkDependentExpressionFolding();
            break;
        case 3:
            checkSubqueryFolding();
            break;
        case 4:
            checkExistsFolding();
            break;
        case 5:
            checkCorrelatedSubqueryFolding();
            break;
        case 6:
            checkInSubqueryFolding();
            break;
        case 7:
            checkAnyAllSubqueryFolding();
            break;
        case 8:
            checkCorrelatedExistsFolding();
            break;
        case 9:
            checkCteFolding();
            break;
        case 10:
            checkDerivedTableFolding();
            break;
        case 11:
            checkJoinOnFolding();
            break;
        case 12:
            checkHavingFolding();
            break;
        default:
            throw new AssertionError("Unsupported CODDTest mode");
        }
    }

    private void checkInFolding() throws SQLException {
        GaussDBTable table = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        List<GaussDBColumn> columns = table.getColumns();
        if (columns.isEmpty()) {
            return;
        }
        GaussDBColumn column = Randomly.fromList(columns);
        GaussDBExpression constant = GaussDBExpressionGenerator.generateConstant(state.getRandomly(), column.getType());
        GaussDBColumnReference columnReference = new GaussDBColumnReference(column, "t");
        GaussDBAggregate count = new GaussDBAggregate(GaussDBAggregate.Function.COUNT,
                Collections.singletonList(new GaussDBStar()), GaussDBDataType.INT);
        GaussDBExpressionBag equalityBag = new GaussDBExpressionBag(
                binaryOperation(columnReference, constant, GaussDBBinaryOperation.Operator.EQUALS,
                        GaussDBDataType.BOOLEAN));
        GaussDBSelect originalSelect = new GaussDBSelect();
        originalSelect.setFetchColumns(Collections.singletonList(new GaussDBAlias(count, "count")));
        originalSelect.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), "t")));
        originalSelect.setWhereClause(equalityBag);
        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);
        GaussDBSelect auxiliarySelect = selectColumnFrom(table, column, "t");
        auxiliarySelect.setWhereClause(binaryOperation(new GaussDBColumnReference(column, "t"), constant,
                GaussDBBinaryOperation.Operator.EQUALS, GaussDBDataType.BOOLEAN));
        String auxiliaryQuery = GaussDBToStringVisitor.asString(auxiliarySelect);

        SQLConnection con = state.getConnection();
        try (Statement s = con.createStatement()) {
            List<String> originalResults = executeAndGetRows(s, originalQuery);
            List<String> auxiliaryResults = executeAndGetValues(s, auxiliaryQuery);

            equalityBag.updateInnerExpression(new GaussDBInOperation(columnReference,
                    auxiliaryResults.isEmpty() ? Collections.singletonList(GaussDBConstant.createNullConstant())
                            : expressionsFromStrings(auxiliaryResults, column.getType()),
                    false));
            String foldedQuery = GaussDBToStringVisitor.asString(originalSelect);
            List<String> foldedResults = executeAndGetRows(s, foldedQuery.toString());

            assertEquivalent(auxiliaryQuery, originalQuery, foldedQuery, originalResults, foldedResults);
        }
    }

    private void checkIndependentExpressionFolding() throws SQLException {
        GaussDBTable table = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        GaussDBExpression expressionNode = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpressionBag expressionBag = new GaussDBExpressionBag(expressionNode);
        GaussDBSelect auxiliarySelect = new GaussDBSelect();
        auxiliarySelect.setFetchColumns(Collections.singletonList(new GaussDBAlias(expressionNode, "value")));
        auxiliarySelect.setFromList(Collections.emptyList());
        String auxiliaryQuery = GaussDBToStringVisitor.asString(auxiliarySelect);
        GaussDBSelect originalSelect = selectStarFrom(table, "t");
        originalSelect.setWhereClause(expressionBag);
        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);

        SQLConnection con = state.getConnection();
        try (Statement s = con.createStatement()) {
            String[] scalar = executeSingleScalar(s, auxiliaryQuery);
            if (scalar == null) {
                return;
            }
            String foldedConstant = scalar[0] == null ? "NULL" : booleanLiteral(scalar[0]);
            expressionBag.updateInnerExpression(constantFromValue(foldedConstant, GaussDBDataType.BOOLEAN));
            String foldedQuery = GaussDBToStringVisitor.asString(originalSelect);
            List<String> originalResults = executeAndGetRows(s, originalQuery);
            List<String> foldedResults = executeAndGetRows(s, foldedQuery);
            assertEquivalent(auxiliaryQuery, originalQuery, foldedQuery, originalResults, foldedResults);
        }
    }

    private void checkDependentExpressionFolding() throws SQLException {
        GaussDBTable table = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        List<GaussDBColumn> columns = table.getColumns();
        if (columns.isEmpty()) {
            return;
        }
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(table.getColumns());
        generator.setTableAlias("t");
        GaussDBExpression expressionNode = generator.generateExpression(GaussDBDataType.BOOLEAN);
        GaussDBExpressionBag expressionBag = new GaussDBExpressionBag(expressionNode);
        GaussDBSelect auxiliarySelect = new GaussDBSelect();
        auxiliarySelect.setFetchColumns(Arrays.asList(new GaussDBAlias(ctidReference("t"), "ctid"),
                new GaussDBAlias(expressionNode, "value")));
        auxiliarySelect.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), "t")));
        String auxiliaryQuery = GaussDBToStringVisitor.asString(auxiliarySelect);
        GaussDBSelect originalSelect = selectStarFrom(table, "t");
        originalSelect.setWhereClause(expressionBag);
        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);

        SQLConnection con = state.getConnection();
        try (Statement s = con.createStatement()) {
            List<String[]> auxiliaryRows = executeTwoColumnRows(s, auxiliaryQuery);
            if (auxiliaryRows.isEmpty()) {
                return;
            }

            List<GaussDBExpression> conditions = new ArrayList<>();
            List<GaussDBExpression> results = new ArrayList<>();
            for (String[] row : auxiliaryRows) {
                conditions.add(binaryOperation(ctidReference("t"), constantFromValue(row[0], GaussDBDataType.TEXT),
                        GaussDBBinaryOperation.Operator.EQUALS, GaussDBDataType.BOOLEAN));
                results.add(constantFromValue(row[1], GaussDBDataType.BOOLEAN));
            }
            GaussDBCaseOperation caseOperation = new GaussDBCaseOperation(conditions, results, null,
                    GaussDBDataType.BOOLEAN);
            GaussDBCastOperation castOperation = new GaussDBCastOperation(caseOperation, GaussDBDataType.BOOLEAN);
            expressionBag.updateInnerExpression(castOperation);
            String foldedQuery = GaussDBToStringVisitor.asString(originalSelect);

            List<String> originalResults = executeAndGetRows(s, originalQuery);
            List<String> foldedResults = executeAndGetRows(s, foldedQuery.toString());
            assertEquivalent(auxiliaryQuery, originalQuery, foldedQuery, originalResults, foldedResults);
        }
    }

    private void checkSubqueryFolding() throws SQLException {
        GaussDBTable targetTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBColumn targetColumn = getNumericColumn(targetTable);
        if (targetColumn == null) {
            return;
        }
        GaussDBTable sourceTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBColumn sourceColumn = getNumericColumn(sourceTable);
        if (sourceColumn == null) {
            return;
        }
        String aggregate = Randomly.fromOptions("MAX", "MIN", "COUNT");
        GaussDBSelect subquerySelect = selectAggregateFrom(sourceTable, sourceColumn, aggregate, "s");
        String subquery = GaussDBToStringVisitor.asString(subquerySelect);
        GaussDBExpressionBag subqueryBag = new GaussDBExpressionBag(new GaussDBSubquery(subquerySelect));
        GaussDBSelect originalSelect = selectStarFrom(targetTable, "t");
        originalSelect.setWhereClause(binaryOperation(new GaussDBColumnReference(targetColumn, "t"),
                subqueryBag, GaussDBBinaryOperation.Operator.GREATER, GaussDBDataType.BOOLEAN));
        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);

        SQLConnection con = state.getConnection();
        try (Statement s = con.createStatement()) {
            String[] scalar = executeSingleScalar(s, GaussDBToStringVisitor.asString(subquerySelect));
            if (scalar == null) {
                return;
            }
            String foldedConstant = scalar[0] == null ? "NULL" : scalar[0];
            subqueryBag.updateInnerExpression(constantFromValue(foldedConstant, targetColumn.getType()));
            String foldedQuery = GaussDBToStringVisitor.asString(originalSelect);
            List<String> originalResults = executeAndGetRows(s, originalQuery);
            List<String> foldedResults = executeAndGetRows(s, foldedQuery);
            assertEquivalent(subquery, originalQuery, foldedQuery, originalResults, foldedResults);
        }
    }

    private void checkExistsFolding() throws SQLException {
        GaussDBTable targetTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBTable sourceTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        List<GaussDBColumn> sourceColumns = sourceTable.getColumns();
        if (sourceColumns.isEmpty()) {
            return;
        }
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(sourceColumns);
        generator.setTableAlias("s");
        GaussDBSelect subquerySelect = new GaussDBSelect();
        subquerySelect.setFetchColumns(Collections.singletonList(GaussDBConstant.createIntConstant(1)));
        subquerySelect.setFromList(
                Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(sourceTable), "s")));
        subquerySelect.setWhereClause(generator.generateExpression(GaussDBDataType.BOOLEAN));
        GaussDBExists exists = new GaussDBExists(new GaussDBSubquery(subquerySelect), false);
        GaussDBExpressionBag existsBag = new GaussDBExpressionBag(exists);
        GaussDBSelect auxiliarySelect = new GaussDBSelect();
        auxiliarySelect.setFetchColumns(Collections.singletonList(new GaussDBAlias(exists, "exists")));
        auxiliarySelect.setFromList(Collections.emptyList());
        String auxiliaryQuery = GaussDBToStringVisitor.asString(auxiliarySelect);
        GaussDBSelect originalSelect = selectStarFrom(targetTable, "t");
        originalSelect.setWhereClause(existsBag);
        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);

        SQLConnection con = state.getConnection();
        try (Statement s = con.createStatement()) {
            String[] scalar = executeSingleScalar(s, auxiliaryQuery);
            if (scalar == null) {
                return;
            }
            String foldedConstant = scalar[0] == null ? "NULL" : booleanLiteral(scalar[0]);
            existsBag.updateInnerExpression(constantFromValue(foldedConstant, GaussDBDataType.BOOLEAN));
            String foldedQuery = GaussDBToStringVisitor.asString(originalSelect);
            List<String> originalResults = executeAndGetRows(s, originalQuery);
            List<String> foldedResults = executeAndGetRows(s, foldedQuery);
            assertEquivalent(auxiliaryQuery, originalQuery, foldedQuery, originalResults, foldedResults);
        }
    }

    private void checkCorrelatedSubqueryFolding() throws SQLException {
        GaussDBTable outerTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBTable innerTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBColumn outerColumn = getNumericColumn(outerTable);
        GaussDBColumn innerColumn = getNumericColumn(innerTable);
        if (outerColumn == null || innerColumn == null) {
            return;
        }
        String aggregate = Randomly.fromOptions("MAX", "MIN", "COUNT");
        GaussDBSelect innerSelect = selectAggregateFrom(innerTable, innerColumn, aggregate, "i");
        innerSelect.setWhereClause(binaryOperation(new GaussDBColumnReference(innerColumn, "i"),
                new GaussDBColumnReference(outerColumn, "o"), GaussDBBinaryOperation.Operator.GREATER,
                GaussDBDataType.BOOLEAN));
        GaussDBSubquery subquery = new GaussDBSubquery(innerSelect);
        GaussDBExpressionBag subqueryBag = new GaussDBExpressionBag(subquery);
        GaussDBSelect auxiliarySelect = new GaussDBSelect();
        auxiliarySelect.setFetchColumns(Arrays.asList(new GaussDBAlias(ctidReference("o"), "ctid"),
                new GaussDBAlias(subquery, "value")));
        auxiliarySelect.setFromList(Collections
                .singletonList(new GaussDBAlias(new GaussDBTableReference(outerTable), "o")));
        String auxiliaryQuery = GaussDBToStringVisitor.asString(auxiliarySelect);
        GaussDBSelect originalSelect = selectStarFrom(outerTable, "o");
        originalSelect.setWhereClause(binaryOperation(new GaussDBColumnReference(outerColumn, "o"), subqueryBag,
                GaussDBBinaryOperation.Operator.GREATER, GaussDBDataType.BOOLEAN));
        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);

        SQLConnection con = state.getConnection();
        try (Statement s = con.createStatement()) {
            List<String[]> auxiliaryRows = executeTwoColumnRows(s, auxiliaryQuery);
            if (auxiliaryRows.isEmpty()) {
                return;
            }
            List<GaussDBExpression> conditions = new ArrayList<>();
            List<GaussDBExpression> results = new ArrayList<>();
            for (String[] row : auxiliaryRows) {
                conditions.add(binaryOperation(ctidReference("o"), constantFromValue(row[0], GaussDBDataType.TEXT),
                        GaussDBBinaryOperation.Operator.EQUALS, GaussDBDataType.BOOLEAN));
                results.add(constantFromValue(row[1], outerColumn.getType()));
            }
            GaussDBCaseOperation caseOperation = new GaussDBCaseOperation(conditions, results, null,
                    outerColumn.getType());
            GaussDBCastOperation castOperation = new GaussDBCastOperation(caseOperation, outerColumn.getType());
            subqueryBag.updateInnerExpression(castOperation);
            String foldedQuery = GaussDBToStringVisitor.asString(originalSelect);
            List<String> originalResults = executeAndGetRows(s, originalQuery);
            List<String> foldedResults = executeAndGetRows(s, foldedQuery.toString());
            assertEquivalent(auxiliaryQuery, originalQuery, foldedQuery, originalResults, foldedResults);
        }
    }

    private void checkInSubqueryFolding() throws SQLException {
        GaussDBTable targetTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBTable sourceTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBColumn targetColumn = getNumericColumn(targetTable);
        GaussDBColumn sourceColumn = getNumericColumn(sourceTable);
        if (targetColumn == null || sourceColumn == null) {
            return;
        }
        GaussDBSelect auxiliarySelect = selectColumnFrom(sourceTable, sourceColumn, "s");
        String auxiliaryQuery = GaussDBToStringVisitor.asString(auxiliarySelect);
        GaussDBInSubquery inSubquery = new GaussDBInSubquery(new GaussDBColumnReference(targetColumn, "t"),
                new GaussDBSubquery(auxiliarySelect), false);
        GaussDBExpressionBag inSubqueryBag = new GaussDBExpressionBag(inSubquery);
        GaussDBSelect originalSelect = selectStarFrom(targetTable, "t");
        originalSelect.setWhereClause(inSubqueryBag);
        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);

        SQLConnection con = state.getConnection();
        try (Statement s = con.createStatement()) {
            List<String> auxiliaryResults = executeAndGetValues(s, auxiliaryQuery);
            if (auxiliaryResults.isEmpty()) {
                return;
            }
            inSubqueryBag.updateInnerExpression(new GaussDBInOperation(
                    new GaussDBColumnReference(targetColumn, "t"),
                    expressionsFromStrings(auxiliaryResults, sourceColumn.getType()), false));
            String foldedQuery = GaussDBToStringVisitor.asString(originalSelect);
            List<String> originalResults = executeAndGetRows(s, originalQuery);
            List<String> foldedResults = executeAndGetRows(s, foldedQuery.toString());
            assertEquivalent(auxiliaryQuery, originalQuery, foldedQuery, originalResults, foldedResults);
        }
    }

    private void checkAnyAllSubqueryFolding() throws SQLException {
        GaussDBTable targetTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBTable sourceTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBColumn targetColumn = getNumericColumn(targetTable);
        GaussDBColumn sourceColumn = getNumericColumn(sourceTable);
        if (targetColumn == null || sourceColumn == null) {
            return;
        }
        GaussDBAnyAllOperation.Quantifier quantifier = GaussDBAnyAllOperation.Quantifier.getRandom();
        GaussDBSelect auxiliarySelect = selectColumnFrom(sourceTable, sourceColumn, "s");
        String auxiliaryQuery = GaussDBToStringVisitor.asString(auxiliarySelect);
        GaussDBAnyAllOperation anyAllOperation = new GaussDBAnyAllOperation(
                new GaussDBColumnReference(targetColumn, "t"), new GaussDBSubquery(auxiliarySelect),
                GaussDBBinaryOperation.Operator.GREATER, quantifier);
        GaussDBExpressionBag anyAllBag = new GaussDBExpressionBag(anyAllOperation);
        GaussDBSelect originalSelect = selectStarFrom(targetTable, "t");
        originalSelect.setWhereClause(anyAllBag);
        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);

        SQLConnection con = state.getConnection();
        try (Statement s = con.createStatement()) {
            List<String> auxiliaryResults = executeAndGetValues(s, auxiliaryQuery);
            if (auxiliaryResults.isEmpty()) {
                return;
            }
            anyAllBag.updateInnerExpression(new GaussDBAnyAllOperation(
                    new GaussDBColumnReference(targetColumn, "t"),
                    valuesFromStrings(auxiliaryResults, sourceColumn.getType()),
                    GaussDBBinaryOperation.Operator.GREATER, quantifier));
            String foldedQuery = GaussDBToStringVisitor.asString(originalSelect);
            List<String> originalResults = executeAndGetRows(s, originalQuery);
            List<String> foldedResults = executeAndGetRows(s, foldedQuery.toString());
            assertEquivalent(auxiliaryQuery, originalQuery, foldedQuery, originalResults, foldedResults);
        }
    }

    private void checkCorrelatedExistsFolding() throws SQLException {
        GaussDBTable outerTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBTable innerTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBColumn outerColumn = getNumericColumn(outerTable);
        GaussDBColumn innerColumn = getNumericColumn(innerTable);
        if (outerColumn == null || innerColumn == null) {
            return;
        }
        GaussDBSelect innerSelect = new GaussDBSelect();
        innerSelect.setFetchColumns(Collections.singletonList(GaussDBConstant.createIntConstant(1)));
        innerSelect.setFromList(
                Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(innerTable), "i")));
        innerSelect.setWhereClause(binaryOperation(new GaussDBColumnReference(innerColumn, "i"),
                new GaussDBColumnReference(outerColumn, "o"), GaussDBBinaryOperation.Operator.GREATER,
                GaussDBDataType.BOOLEAN));
        GaussDBSubquery subquery = new GaussDBSubquery(innerSelect);
        GaussDBExists exists = new GaussDBExists(subquery, false);
        GaussDBExpressionBag existsBag = new GaussDBExpressionBag(exists);
        GaussDBSelect auxiliarySelect = new GaussDBSelect();
        auxiliarySelect.setFetchColumns(Arrays.asList(new GaussDBAlias(ctidReference("o"), "ctid"),
                new GaussDBAlias(exists, "value")));
        auxiliarySelect.setFromList(Collections
                .singletonList(new GaussDBAlias(new GaussDBTableReference(outerTable), "o")));
        String auxiliaryQuery = GaussDBToStringVisitor.asString(auxiliarySelect);
        GaussDBSelect originalSelect = selectStarFrom(outerTable, "o");
        originalSelect.setWhereClause(existsBag);
        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);

        SQLConnection con = state.getConnection();
        try (Statement s = con.createStatement()) {
            List<String[]> auxiliaryRows = executeTwoColumnRows(s, auxiliaryQuery);
            if (auxiliaryRows.isEmpty()) {
                return;
            }
            List<GaussDBExpression> conditions = new ArrayList<>();
            List<GaussDBExpression> results = new ArrayList<>();
            for (String[] row : auxiliaryRows) {
                conditions.add(binaryOperation(ctidReference("o"), constantFromValue(row[0], GaussDBDataType.TEXT),
                        GaussDBBinaryOperation.Operator.EQUALS, GaussDBDataType.BOOLEAN));
                results.add(constantFromValue(row[1], GaussDBDataType.BOOLEAN));
            }
            GaussDBCaseOperation caseOperation = new GaussDBCaseOperation(conditions, results, null,
                    GaussDBDataType.BOOLEAN);
            GaussDBCastOperation castOperation = new GaussDBCastOperation(caseOperation, GaussDBDataType.BOOLEAN);
            existsBag.updateInnerExpression(castOperation);
            String foldedQuery = GaussDBToStringVisitor.asString(originalSelect);
            List<String> originalResults = executeAndGetRows(s, originalQuery);
            List<String> foldedResults = executeAndGetRows(s, foldedQuery.toString());
            assertEquivalent(auxiliaryQuery, originalQuery, foldedQuery, originalResults, foldedResults);
        }
    }

    private void checkCteFolding() throws SQLException {
        GaussDBTable sourceTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBColumn sourceColumn = getNumericColumn(sourceTable);
        if (sourceColumn == null) {
            return;
        }
        GaussDBSelect auxiliarySelect = selectColumnFrom(sourceTable, sourceColumn, "s");
        String auxiliaryQuery = GaussDBToStringVisitor.asString(auxiliarySelect);
        GaussDBExpressionBag cteBag = new GaussDBExpressionBag(new GaussDBSubquery(auxiliarySelect));
        GaussDBSelect originalSelect = selectStarFromCte("cte");
        originalSelect.setWithClause(new GaussDBWithClause("cte(col)", cteBag));
        originalSelect.setWhereClause(binaryOperation(
                new GaussDBColumnReference(new GaussDBColumn("col", sourceColumn.getType()), "cte"),
                GaussDBConstant.createIntConstant(0), GaussDBBinaryOperation.Operator.GREATER,
                GaussDBDataType.BOOLEAN));
        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);

        SQLConnection con = state.getConnection();
        try (Statement s = con.createStatement()) {
            List<String> auxiliaryResults = executeAndGetValues(s, auxiliaryQuery);
            if (auxiliaryResults.isEmpty()) {
                return;
            }
            GaussDBValues values = valuesFromStrings(auxiliaryResults, sourceColumn.getType());
            cteBag.updateInnerExpression(values);
            String foldedQuery = GaussDBToStringVisitor.asString(originalSelect);
            List<String> originalResults = executeAndGetRows(s, originalQuery);
            List<String> foldedResults = executeAndGetRows(s, foldedQuery.toString());
            assertEquivalent(auxiliaryQuery, originalQuery, foldedQuery, originalResults, foldedResults);
        }
    }

    private void checkDerivedTableFolding() throws SQLException {
        GaussDBTable sourceTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBColumn sourceColumn = getNumericColumn(sourceTable);
        if (sourceColumn == null) {
            return;
        }
        GaussDBSelect auxiliarySelect = selectColumnFrom(sourceTable, sourceColumn, "s");
        String auxiliaryQuery = GaussDBToStringVisitor.asString(auxiliarySelect);
        GaussDBExpressionBag derivedBag = new GaussDBExpressionBag(new GaussDBSubquery(auxiliarySelect));
        GaussDBSelect originalSelect = selectStarFromDerivedTable("sub");
        originalSelect.setFromList(Collections.singletonList(new GaussDBAlias(derivedBag, "sub(col)")));
        originalSelect.setWhereClause(binaryOperation(
                new GaussDBColumnReference(new GaussDBColumn("col", sourceColumn.getType()), "sub"),
                GaussDBConstant.createIntConstant(0), GaussDBBinaryOperation.Operator.GREATER,
                GaussDBDataType.BOOLEAN));
        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);

        SQLConnection con = state.getConnection();
        try (Statement s = con.createStatement()) {
            List<String> auxiliaryResults = executeAndGetValues(s, auxiliaryQuery);
            if (auxiliaryResults.isEmpty()) {
                return;
            }
            GaussDBValues values = valuesFromStrings(auxiliaryResults, sourceColumn.getType());
            derivedBag.updateInnerExpression(values);
            String foldedQuery = GaussDBToStringVisitor.asString(originalSelect);
            List<String> originalResults = executeAndGetRows(s, originalQuery);
            List<String> foldedResults = executeAndGetRows(s, foldedQuery.toString());
            assertEquivalent(auxiliaryQuery, originalQuery, foldedQuery, originalResults, foldedResults);
        }
    }

    private void checkJoinOnFolding() throws SQLException {
        GaussDBTable leftTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBTable rightTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBColumn leftColumn = getNumericColumn(leftTable);
        GaussDBColumn rightColumn = getNumericColumn(rightTable);
        if (leftColumn == null || rightColumn == null) {
            return;
        }
        String aggregate = Randomly.fromOptions("MAX", "MIN", "COUNT");
        GaussDBSelect subquerySelect = selectAggregateFrom(rightTable, rightColumn, aggregate, "r");
        String subquery = GaussDBToStringVisitor.asString(subquerySelect);
        GaussDBExpressionBag subqueryBag = new GaussDBExpressionBag(new GaussDBSubquery(subquerySelect));
        GaussDBSelect originalSelect = new GaussDBSelect();
        originalSelect.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        originalSelect.setFromList(
                Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(leftTable), "l")));
        GaussDBJoin join = new GaussDBJoin(new GaussDBAlias(new GaussDBTableReference(leftTable), "l"),
                new GaussDBAlias(new GaussDBTableReference(rightTable), "r"), GaussDBJoin.JoinType.INNER,
                binaryOperation(new GaussDBColumnReference(leftColumn, "l"), subqueryBag,
                        GaussDBBinaryOperation.Operator.GREATER, GaussDBDataType.BOOLEAN));
        originalSelect.setJoinClauses(Collections.singletonList(join));
        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);

        SQLConnection con = state.getConnection();
        try (Statement s = con.createStatement()) {
            String[] scalar = executeSingleScalar(s, GaussDBToStringVisitor.asString(subquerySelect));
            if (scalar == null) {
                return;
            }
            String foldedConstant = scalar[0] == null ? "NULL" : scalar[0];
            subqueryBag.updateInnerExpression(constantFromValue(foldedConstant, leftColumn.getType()));
            String foldedQuery = GaussDBToStringVisitor.asString(originalSelect);
            List<String> originalResults = executeAndGetRows(s, originalQuery);
            List<String> foldedResults = executeAndGetRows(s, foldedQuery);
            assertEquivalent(subquery, originalQuery, foldedQuery, originalResults, foldedResults);
        }
    }

    private void checkHavingFolding() throws SQLException {
        GaussDBTable targetTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBTable sourceTable = state.getSchema().getRandomTable(GaussDBTable::isInsertable);
        GaussDBColumn targetColumn = getNumericColumn(targetTable);
        GaussDBColumn sourceColumn = getNumericColumn(sourceTable);
        if (targetColumn == null || sourceColumn == null) {
            return;
        }
        String aggregate = targetTable.getName().equals(sourceTable.getName())
                && state.getDbmsSpecificOptions() != null
                && state.getDbmsSpecificOptions().bypassBooleanTestRangeBug
                        ? Randomly.fromOptions("MAX", "MIN")
                        : Randomly.fromOptions("MAX", "MIN", "COUNT");
        GaussDBSelect subquerySelect = selectAggregateFrom(sourceTable, sourceColumn, aggregate, "s");
        String subquery = GaussDBToStringVisitor.asString(subquerySelect);
        GaussDBExpressionBag subqueryBag = new GaussDBExpressionBag(new GaussDBSubquery(subquerySelect));
        GaussDBAggregate count = new GaussDBAggregate(GaussDBAggregate.Function.COUNT,
                Collections.singletonList(new GaussDBStar()), GaussDBDataType.INT);
        GaussDBSelect originalSelect = new GaussDBSelect();
        originalSelect.setFetchColumns(Arrays.asList(
                new GaussDBAlias(new GaussDBColumnReference(targetColumn, "t"), "col"),
                new GaussDBAlias(count, "count")));
        originalSelect.setFromList(
                Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(targetTable), "t")));
        originalSelect.setGroupByExpressions(
                Collections.singletonList(new GaussDBColumnReference(targetColumn, "t")));
        originalSelect.setHavingClause(binaryOperation(count, subqueryBag,
                GaussDBBinaryOperation.Operator.GREATER, GaussDBDataType.BOOLEAN));
        String originalQuery = GaussDBToStringVisitor.asString(originalSelect);

        SQLConnection con = state.getConnection();
        try (Statement s = con.createStatement()) {
            String[] scalar = executeSingleScalar(s, GaussDBToStringVisitor.asString(subquerySelect));
            if (scalar == null) {
                return;
            }
            String foldedConstant = scalar[0] == null ? "NULL" : scalar[0];
            subqueryBag.updateInnerExpression(constantFromValue(foldedConstant, GaussDBDataType.INT));
            String foldedQuery = GaussDBToStringVisitor.asString(originalSelect);
            List<String> originalResults = executeAndGetRows(s, originalQuery);
            List<String> foldedResults = executeAndGetRows(s, foldedQuery);
            assertEquivalent(subquery, originalQuery, foldedQuery, originalResults, foldedResults);
        }
    }

    private GaussDBSelect selectColumnFrom(GaussDBTable table, GaussDBColumn column, String alias) {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(
                new GaussDBAlias(new GaussDBColumnReference(column, alias), "col")));
        select.setFromList(
                Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), alias)));
        return select;
    }

    private GaussDBSelect selectStarFromCte(String alias) {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        select.setFromList(Collections.singletonList(
                new GaussDBAlias(new GaussDBTableReference(new GaussDBTable(alias, Collections.emptyList(),
                        null, false, true)), alias)));
        return select;
    }

    private GaussDBSelect selectStarFrom(GaussDBTable table, String alias) {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        select.setFromList(
                Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), alias)));
        return select;
    }

    private GaussDBSelect selectAggregateFrom(GaussDBTable table, GaussDBColumn column, String aggregate,
            String alias) {
        GaussDBAggregate.Function function = GaussDBAggregate.Function.valueOf(aggregate);
        GaussDBExpression argument = function == GaussDBAggregate.Function.COUNT ? new GaussDBStar()
                : new GaussDBColumnReference(column, alias);
        GaussDBDataType resultType = function == GaussDBAggregate.Function.COUNT ? GaussDBDataType.INT
                : column.getType();
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(
                new GaussDBAlias(new GaussDBAggregate(function, Collections.singletonList(argument), resultType), "agg")));
        select.setFromList(
                Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), alias)));
        return select;
    }

    private GaussDBExpression ctidReference(String alias) {
        GaussDBExpression tableoid = new GaussDBCastOperation(
                new GaussDBColumnReference(new GaussDBColumn("tableoid", GaussDBDataType.INT), alias),
                GaussDBDataType.TEXT);
        GaussDBExpression ctid = new GaussDBCastOperation(
                new GaussDBColumnReference(new GaussDBColumn("ctid", GaussDBDataType.TEXT), alias),
                GaussDBDataType.TEXT);
        return new GaussDBFunction("concat", Arrays.asList(tableoid, ctid), GaussDBDataType.TEXT);
    }

    private GaussDBSelect selectStarFromDerivedTable(String alias) {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(Collections.singletonList(new GaussDBStar()));
        return select;
    }

    private GaussDBValues valuesFromStrings(List<String> values, GaussDBDataType type) {
        List<GaussDBExpression> rows = new ArrayList<>();
        for (String value : values) {
            rows.add(constantFromValue(value, type));
        }
        return new GaussDBValues(rows);
    }

    private List<GaussDBExpression> expressionsFromStrings(List<String> values, GaussDBDataType type) {
        List<GaussDBExpression> expressions = new ArrayList<>();
        for (String value : values) {
            expressions.add(constantFromValue(value, type));
        }
        return expressions;
    }

    private GaussDBConstant constantFromValue(String value, GaussDBDataType type) {
        if (value == null || "NULL".equalsIgnoreCase(value) || "NaN".equalsIgnoreCase(value)
                || "Infinity".equalsIgnoreCase(value) || "-Infinity".equalsIgnoreCase(value)) {
            return GaussDBConstant.createNullConstant();
        }
        switch (type) {
        case INT:
            return GaussDBConstant.createIntConstant(Long.parseLong(value));
        case BOOLEAN:
            return GaussDBConstant.createBooleanConstant("TRUE".contentEquals(booleanLiteral(value)));
        case TEXT:
            return GaussDBConstant.createTextConstant(value);
        case DECIMAL:
        case FLOAT:
        case REAL:
            return GaussDBConstant.createDecimalConstant(new java.math.BigDecimal(value));
        case DATE:
            return GaussDBConstant.createDateConstant(value);
        case TIMESTAMP:
            return GaussDBConstant.createTimestampConstant(value);
        case TIMESTAMPTZ:
            return GaussDBConstant.createTimestamptzConstant(value);
        case TIME:
            return GaussDBConstant.createTimeConstant(value);
        case INTERVAL:
            return GaussDBConstant.createIntervalConstant(value);
        default:
            throw new AssertionError(type);
        }
    }

    private GaussDBExpression binaryOperation(GaussDBExpression left, GaussDBExpression right,
            GaussDBBinaryOperation.Operator operator, GaussDBDataType type) {
        return new GaussDBBinaryOperation(left, right, operator, type);
    }

    private GaussDBColumn getNumericColumn(GaussDBTable table) {
        List<GaussDBColumn> columns = table.getColumns();
        List<GaussDBColumn> numericColumns = new ArrayList<>();
        for (GaussDBColumn column : columns) {
            switch (column.getType()) {
            case INT:
            case DECIMAL:
            case FLOAT:
            case REAL:
                numericColumns.add(column);
                break;
            default:
                break;
            }
        }
        return numericColumns.isEmpty() ? null : Randomly.fromList(numericColumns);
    }

    private String booleanLiteral(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
        case "t":
        case "true":
        case "1":
        case "yes":
            return "TRUE";
        case "f":
        case "false":
        case "0":
        case "no":
            return "FALSE";
        default:
            return value;
        }
    }

    private List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy;
    }

    private void assertEquivalent(String auxiliaryQuery, String originalQuery, String foldedQuery,
            List<String> originalResults, List<String> foldedResults) {
        lastOriginalQuery = originalQuery;
        if (sorted(originalResults).equals(sorted(foldedResults))) {
            return;
        }
        reproducer = new GaussDBCODDTestReproducer(auxiliaryQuery, originalQuery, foldedQuery);
        throw new AssertionError("CODDTest mismatch\nAuxiliary: " + auxiliaryQuery + "\nOriginal: " + originalQuery
                + "\nFolded: " + foldedQuery + "\nOriginalResult: " + originalResults + "\nFoldedResult: "
                + foldedResults);
    }

    @Override
    public Reproducer<GaussDBGlobalState> getLastReproducer() {
        return reproducer;
    }

    @Override
    public String getLastQueryString() {
        return lastOriginalQuery;
    }

    private List<String> executeAndGetRows(Statement s, String query) throws SQLException {
        List<String> results = new ArrayList<>();
        try (ResultSet rs = s.executeQuery(query)) {
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.add(GaussDBEETOracle.normalizeCellValue(rs.getString(i)));
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

    private String[] executeSingleScalar(Statement s, String query) throws SQLException {
        try (ResultSet rs = s.executeQuery(query)) {
            if (!rs.next()) {
                return null;
            }
            String value = rs.getString(1);
            return new String[] { rs.wasNull() ? null : value };
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        }
    }

    private List<String[]> executeTwoColumnRows(Statement s, String query) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (ResultSet rs = s.executeQuery(query)) {
            while (rs.next()) {
                rows.add(new String[] { rs.getString(1), rs.getString(2) });
            }
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        }
        return rows;
    }

    private List<String> executeAndGetValues(Statement s, String query) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rs = s.executeQuery(query)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        }
        return values;
    }

}
