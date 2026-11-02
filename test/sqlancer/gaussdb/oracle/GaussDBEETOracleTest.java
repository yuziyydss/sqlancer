package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.GaussDBToStringVisitor;
import sqlancer.gaussdb.ast.GaussDBAnyAllOperation;
import sqlancer.gaussdb.ast.GaussDBAggregate;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBExists;
import sqlancer.gaussdb.ast.GaussDBInSubquery;
import sqlancer.gaussdb.ast.GaussDBJoin;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBSelect;
import sqlancer.gaussdb.ast.GaussDBSetOperation;
import sqlancer.gaussdb.ast.GaussDBStar;
import sqlancer.gaussdb.ast.GaussDBSubquery;
import sqlancer.gaussdb.ast.GaussDBTableReference;
import sqlancer.gaussdb.ast.GaussDBAlias;
import sqlancer.gaussdb.ast.GaussDBWindowFrame;
import sqlancer.gaussdb.ast.GaussDBWindowFunction;

class GaussDBEETOracleTest {

    private final GaussDBColumn column = new GaussDBColumn("c0", GaussDBDataType.INT);
    private final GaussDBTable table = new GaussDBTable("t0", List.of(column), null, false, true);

    @Test
    void equivalentExpressionUsesOriginalExpressionOnBothCaseBranches() {
        GaussDBExpression expression = new GaussDBColumnReference(column, "t");
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);

        assertEquals("CASE WHEN FALSE THEN t.c0 ELSE t.c0 END",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.equivalentExpression(expression, condition)));
    }

    @Test
    void selectQueryUsesStableTableAliasAndValueAlias() {
        GaussDBExpression expression = new GaussDBColumnReference(column, "t");

        assertEquals("SELECT t.c0 AS value FROM t0 AS t",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectValueFrom(table, expression)));
    }

    @Test
    void whereQueryTransformsPredicate() {
        GaussDBExpression predicate = GaussDBConstant.createBooleanConstant(true);
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);

        assertEquals("SELECT * FROM t0 AS t WHERE CASE WHEN FALSE THEN TRUE ELSE TRUE END",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarWhere(table,
                        GaussDBEETOracle.equivalentExpression(predicate, condition))));
    }

    @Test
    void havingQueryGroupsByOneColumnAndTransformsPredicate() {
        GaussDBExpression predicate = GaussDBConstant.createBooleanConstant(true);
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);

        assertEquals(
                "SELECT t.c0 AS group_value, COUNT(*) AS row_count FROM t0 AS t "
                        + "GROUP BY t.c0 HAVING CASE WHEN FALSE THEN TRUE ELSE TRUE END",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectGroupByHaving(table, column,
                        GaussDBEETOracle.equivalentExpression(predicate, condition))));
    }

    @Test
    void joinQueryUsesTwoAliasesAndTransformsOnClause() {
        GaussDBExpression predicate = GaussDBConstant.createBooleanConstant(true);
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);

        assertEquals("SELECT * FROM t0 AS l JOIN t0 AS r ON CASE WHEN FALSE THEN TRUE ELSE TRUE END",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarJoin(table, table,
                        GaussDBJoin.JoinType.INNER, GaussDBEETOracle.equivalentExpression(predicate, condition))));
        assertEquals("SELECT * FROM t0 AS l LEFT JOIN t0 AS r ON CASE WHEN FALSE THEN TRUE ELSE TRUE END",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarJoin(table, table, GaussDBJoin.JoinType.LEFT,
                        GaussDBEETOracle.equivalentExpression(predicate, condition))));
        assertEquals("SELECT * FROM t0 AS l RIGHT JOIN t0 AS r ON CASE WHEN FALSE THEN TRUE ELSE TRUE END",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarJoin(table, table,
                        GaussDBJoin.JoinType.RIGHT, GaussDBEETOracle.equivalentExpression(predicate, condition))));
    }

    @Test
    void fullJoinPredicateUsesNullTautologyInsteadOfCase() {
        GaussDBExpression predicate = GaussDBConstant.createBooleanConstant(true);
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);

        assertEquals(
                "SELECT * FROM t0 AS l FULL JOIN t0 AS r ON ((TRUE) AND (((((FALSE) IS NOT NULL)) OR (((FALSE) IS NULL)))))",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarJoin(table, table, GaussDBJoin.JoinType.FULL,
                        GaussDBEETOracle.equivalentFullJoinPredicate(predicate, condition))));
    }

    @Test
    void cteQueryWrapsInnerSelect() {
        GaussDBSelect innerSelect = GaussDBEETOracle.selectStarWhere(table,
                GaussDBConstant.createBooleanConstant(true));

        assertEquals("WITH eet_cte AS ((SELECT * FROM t0 AS t WHERE TRUE)) SELECT * FROM eet_cte AS eet_cte",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarFromCte(innerSelect)));
    }

    @Test
    void existsQueryUsesOuterAlias() {
        GaussDBSelect innerSelect = new GaussDBSelect();
        innerSelect.setFetchColumns(List.of(GaussDBConstant.createIntConstant(1)));
        innerSelect.setFromList(List.of(new GaussDBAlias(new GaussDBTableReference(table), "i")));
        innerSelect.setWhereClause(GaussDBConstant.createBooleanConstant(true));

        GaussDBExists exists = new GaussDBExists(new GaussDBSubquery(innerSelect), false);

        assertEquals("SELECT * FROM t0 AS o WHERE EXISTS (SELECT 1 FROM t0 AS i WHERE TRUE)",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarWhereExists(table, exists)));
    }

    @Test
    void inSubqueryQueryUsesOuterAlias() {
        GaussDBSelect innerSelect = GaussDBEETOracle.selectColumnFrom(table, column, "i");
        GaussDBInSubquery predicate = new GaussDBInSubquery(new GaussDBColumnReference(column, "t"),
                new GaussDBSubquery(innerSelect), false);

        assertEquals("SELECT * FROM t0 AS t WHERE (t.c0) IN (SELECT i.c0 AS value FROM t0 AS i)",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarWhere(table, predicate)));
    }

    @Test
    void anyAllSubqueryQueryUsesOuterAlias() {
        GaussDBSelect innerSelect = GaussDBEETOracle.selectColumnFrom(table, column, "i");
        GaussDBAnyAllOperation predicate = new GaussDBAnyAllOperation(new GaussDBColumnReference(column, "t"),
                new GaussDBSubquery(innerSelect), sqlancer.gaussdb.ast.GaussDBBinaryOperation.Operator.GREATER,
                GaussDBAnyAllOperation.Quantifier.ANY);

        assertEquals("SELECT * FROM t0 AS t WHERE (t.c0  >  ANY (SELECT i.c0 AS value FROM t0 AS i))",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarWhere(table, predicate)));
    }

    @Test
    void derivedTableQueryWrapsInnerSelect() {
        GaussDBSelect innerSelect = GaussDBEETOracle.selectStarWhere(table,
                GaussDBConstant.createBooleanConstant(true));

        assertEquals("SELECT * FROM (SELECT * FROM t0 AS t WHERE TRUE) AS eet_derived",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarFromDerivedTable(innerSelect)));
    }

    @Test
    void notExistsQueryUsesOuterAlias() {
        GaussDBSelect innerSelect = new GaussDBSelect();
        innerSelect.setFetchColumns(List.of(GaussDBConstant.createIntConstant(1)));
        innerSelect.setFromList(List.of(new GaussDBAlias(new GaussDBTableReference(table), "i")));
        innerSelect.setWhereClause(GaussDBConstant.createBooleanConstant(true));

        GaussDBExists exists = new GaussDBExists(new GaussDBSubquery(innerSelect), true);

        assertEquals("SELECT * FROM t0 AS o WHERE NOT EXISTS (SELECT 1 FROM t0 AS i WHERE TRUE)",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarWhereExists(table, exists)));
    }

    @Test
    void notInSubqueryQueryUsesOuterAlias() {
        GaussDBSelect innerSelect = GaussDBEETOracle.selectColumnFrom(table, column, "i");
        GaussDBInSubquery predicate = new GaussDBInSubquery(new GaussDBColumnReference(column, "t"),
                new GaussDBSubquery(innerSelect), true);

        assertEquals("SELECT * FROM t0 AS t WHERE (t.c0) NOT IN (SELECT i.c0 AS value FROM t0 AS i)",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarWhere(table, predicate)));
    }

    @Test
    void scalarSubqueryQueryUsesOuterAlias() {
        GaussDBSelect innerSelect = GaussDBEETOracle.selectColumnFrom(table, column, "i");
        innerSelect.setOrderByClauses(List.of(new GaussDBColumnReference(column, "i")));
        innerSelect.setLimitClause(GaussDBConstant.createIntConstant(1));

        GaussDBExpression predicate = new sqlancer.gaussdb.ast.GaussDBBinaryOperation(
                new GaussDBColumnReference(column, "t"), new GaussDBSubquery(innerSelect),
                sqlancer.gaussdb.ast.GaussDBBinaryOperation.Operator.GREATER, GaussDBDataType.BOOLEAN);

        assertEquals(
                "SELECT * FROM t0 AS t WHERE ((t.c0) > ((SELECT i.c0 AS value FROM t0 AS i ORDER BY i.c0 LIMIT 1)))",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarWhere(table, predicate)));
    }

    @Test
    void distinctQueryTransformsSelectedExpression() {
        GaussDBExpression expression = new GaussDBColumnReference(column, "t");
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);

        GaussDBSelect select = GaussDBEETOracle.selectValueFrom(table,
                GaussDBEETOracle.equivalentExpression(expression, condition));
        select.setDistinct(true);

        assertEquals("SELECT DISTINCT CASE WHEN FALSE THEN t.c0 ELSE t.c0 END AS value FROM t0 AS t",
                GaussDBToStringVisitor.asString(select));
    }

    @Test
    void groupByExpressionQueryTransformsGroupingKey() {
        GaussDBExpression expression = new GaussDBColumnReference(column, "t");
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);

        assertEquals(
                "SELECT CASE WHEN FALSE THEN t.c0 ELSE t.c0 END AS group_value, COUNT(*) AS row_count "
                        + "FROM t0 AS t GROUP BY CASE WHEN FALSE THEN t.c0 ELSE t.c0 END",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectGroupByExpression(table,
                        GaussDBEETOracle.equivalentExpression(expression, condition))));
    }

    @Test
    void groupByIntegerConstantIsWrappedInCast() {
        GaussDBExpression expression = GaussDBConstant.createIntConstant(-31);

        assertEquals(
                "SELECT CAST(-31 AS BIGINT) AS group_value, COUNT(*) AS row_count "
                        + "FROM t0 AS t GROUP BY CAST(-31 AS BIGINT)",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectGroupByExpression(table, expression)));
    }

    @Test
    void groupByTextConstantIsWrappedInCast() {
        GaussDBExpression expression = GaussDBConstant.createTextConstant("abc");

        assertEquals(
                "SELECT CAST('abc' AS TEXT) AS group_value, COUNT(*) AS row_count "
                        + "FROM t0 AS t GROUP BY CAST('abc' AS TEXT)",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectGroupByExpression(table, expression)));
    }

    @Test
    void multiColumnGroupByQueryTransformsOneOrMoreKeys() {
        GaussDBColumn secondColumn = new GaussDBColumn("c1", GaussDBDataType.TEXT);
        GaussDBTable twoColumnTable = new GaussDBTable("t0", List.of(column, secondColumn), null, false, true);
        GaussDBExpression first = new GaussDBColumnReference(column, "t");
        GaussDBExpression second = new GaussDBColumnReference(secondColumn, "t");
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);

        assertEquals("SELECT CASE WHEN FALSE THEN t.c0 ELSE t.c0 END AS group_value0, t.c1 AS group_value1, "
                + "COUNT(*) AS row_count FROM t0 AS t " + "GROUP BY CASE WHEN FALSE THEN t.c0 ELSE t.c0 END, t.c1",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectGroupByExpressions(twoColumnTable,
                        List.of(GaussDBEETOracle.equivalentExpression(first, condition), second))));
    }

    @Test
    void nestedCteQueryUsesDistinctAliases() {
        GaussDBSelect innerSelect = GaussDBEETOracle.selectStarWhere(table,
                GaussDBConstant.createBooleanConstant(true));
        GaussDBSelect innerCte = GaussDBEETOracle.selectStarFromCte(innerSelect, "eet_inner_cte");
        GaussDBSelect outerCte = GaussDBEETOracle.selectStarFromCte(innerCte, "eet_outer_cte");

        assertEquals("WITH eet_outer_cte AS ((WITH eet_inner_cte AS ((SELECT * FROM t0 AS t WHERE TRUE)) "
                + "SELECT * FROM eet_inner_cte AS eet_inner_cte)) " + "SELECT * FROM eet_outer_cte AS eet_outer_cte",
                GaussDBToStringVisitor.asString(outerCte));
    }

    @Test
    void threeTableJoinQueryTransformsOneOrBothOnClauses() {
        GaussDBExpression leftPredicate = GaussDBConstant.createBooleanConstant(true);
        GaussDBExpression rightPredicate = GaussDBConstant.createBooleanConstant(false);
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);

        assertEquals(
                "SELECT * FROM t0 AS l JOIN t0 AS m "
                        + "ON CASE WHEN FALSE THEN TRUE ELSE TRUE END JOIN t0 AS r ON FALSE",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarThreeTableJoin(table, table, table,
                        GaussDBEETOracle.equivalentExpression(leftPredicate, condition), rightPredicate)));
    }

    @Test
    void mixedThreeTableJoinQueryUsesIndependentJoinTypes() {
        GaussDBExpression leftPredicate = GaussDBConstant.createBooleanConstant(true);
        GaussDBExpression rightPredicate = GaussDBConstant.createBooleanConstant(false);
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);

        assertEquals(
                "SELECT * FROM t0 AS l LEFT JOIN t0 AS m " + "ON CASE WHEN FALSE THEN TRUE ELSE TRUE END "
                        + "RIGHT JOIN t0 AS r ON FALSE",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarThreeTableJoin(table, table, table,
                        GaussDBJoin.JoinType.LEFT, GaussDBJoin.JoinType.RIGHT,
                        GaussDBEETOracle.equivalentExpression(leftPredicate, condition), rightPredicate)));
    }

    @Test
    void fullMixedThreeTableJoinUsesNullSafeTransform() {
        GaussDBExpression leftPredicate = GaussDBConstant.createBooleanConstant(true);
        GaussDBExpression rightPredicate = GaussDBConstant.createBooleanConstant(false);
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);

        assertEquals("SELECT * FROM t0 AS l FULL JOIN t0 AS m ON "
                + "((TRUE) AND (((((FALSE) IS NOT NULL)) OR (((FALSE) IS NULL))))) " + "RIGHT JOIN t0 AS r ON FALSE",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarThreeTableJoin(table, table, table,
                        GaussDBJoin.JoinType.FULL, GaussDBJoin.JoinType.RIGHT,
                        GaussDBEETOracle.equivalentFullJoinPredicate(leftPredicate, condition), rightPredicate)));
    }

    @Test
    void joinDerivedTableQueryWrapsRightSide() {
        GaussDBExpression predicate = GaussDBConstant.createBooleanConstant(true);
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);
        GaussDBSelect rightSelect = GaussDBEETOracle.selectColumnFrom(table, column, "i", column.getName());

        assertEquals(
                "SELECT * FROM t0 AS l JOIN (SELECT i.c0 AS c0 FROM t0 AS i) AS r "
                        + "ON CASE WHEN FALSE THEN TRUE ELSE TRUE END",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarJoinDerivedTable(table, rightSelect,
                        GaussDBJoin.JoinType.INNER, GaussDBEETOracle.equivalentExpression(predicate, condition))));
    }

    @Test
    void nestedDerivedTableQueryUsesDistinctAliases() {
        GaussDBSelect innerSelect = GaussDBEETOracle.selectStarWhere(table,
                GaussDBConstant.createBooleanConstant(true));
        GaussDBSelect middleSelect = GaussDBEETOracle.selectStarFromDerivedTable(innerSelect, "eet_inner_derived");
        GaussDBSelect outerSelect = GaussDBEETOracle.selectStarFromDerivedTable(middleSelect, "eet_outer_derived");

        assertEquals("SELECT * FROM (SELECT * FROM (SELECT * FROM t0 AS t WHERE TRUE) "
                + "AS eet_inner_derived) AS eet_outer_derived", GaussDBToStringVisitor.asString(outerSelect));
    }

    @Test
    void countAggregateQueryTransformsCountArgument() {
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);
        GaussDBAggregate count = new GaussDBAggregate(GaussDBAggregate.Function.COUNT,
                List.of(GaussDBEETOracle.equivalentExpression(GaussDBConstant.createIntConstant(1), condition)),
                GaussDBDataType.INT);

        assertEquals("SELECT COUNT(CASE WHEN FALSE THEN 1 ELSE 1 END) AS row_count FROM t0 AS t",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectAggregateFrom(table, count)));
    }

    @Test
    void windowFunctionQueryTransformsPartitionExpression() {
        GaussDBExpression expression = new GaussDBColumnReference(column, "t");
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);
        GaussDBWindowFunction windowFunction = new GaussDBWindowFunction("row_number", List.of(),
                List.of(GaussDBEETOracle.equivalentExpression(expression, condition)), List.of(expression),
                GaussDBDataType.INT);

        assertEquals(
                "SELECT row_number() OVER (PARTITION BY CASE WHEN FALSE THEN t.c0 ELSE t.c0 END "
                        + "ORDER BY t.c0) AS value FROM t0 AS t",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectValueFrom(table, windowFunction)));
    }

    @Test
    void lagLeadWindowFunctionQueryTransformsPartitionExpression() {
        GaussDBExpression expression = new GaussDBColumnReference(column, "t");
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);
        GaussDBWindowFunction windowFunction = new GaussDBWindowFunction("lag", List.of(expression),
                List.of(GaussDBEETOracle.equivalentExpression(expression, condition)), List.of(expression),
                GaussDBDataType.INT);

        assertEquals(
                "SELECT lag(t.c0) OVER (PARTITION BY CASE WHEN FALSE THEN t.c0 ELSE t.c0 END "
                        + "ORDER BY t.c0) AS value FROM t0 AS t",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectValueFrom(table, windowFunction)));
    }

    @Test
    void valueWindowFunctionsRenderArguments() {
        GaussDBExpression expression = new GaussDBColumnReference(column, "t");
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);

        GaussDBWindowFunction firstValue = new GaussDBWindowFunction("first_value", List.of(expression),
                List.of(GaussDBEETOracle.equivalentExpression(expression, condition)), List.of(expression),
                GaussDBDataType.INT);
        GaussDBWindowFunction lastValue = new GaussDBWindowFunction("last_value", List.of(expression),
                List.of(expression), List.of(expression), GaussDBDataType.INT);
        GaussDBWindowFunction ntile = new GaussDBWindowFunction("ntile", List.of(GaussDBConstant.createIntConstant(2)),
                List.of(expression), List.of(expression), GaussDBDataType.INT);
        GaussDBWindowFunction nthValue = new GaussDBWindowFunction("nth_value",
                List.of(expression, GaussDBConstant.createIntConstant(2)),
                List.of(GaussDBEETOracle.equivalentExpression(expression, condition)), List.of(expression),
                GaussDBDataType.INT);

        assertEquals(
                "SELECT first_value(t.c0) OVER (PARTITION BY CASE WHEN FALSE THEN t.c0 ELSE t.c0 END "
                        + "ORDER BY t.c0) AS value FROM t0 AS t",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectValueFrom(table, firstValue)));
        assertEquals("SELECT last_value(t.c0) OVER (PARTITION BY t.c0 ORDER BY t.c0) AS value FROM t0 AS t",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectValueFrom(table, lastValue)));
        assertEquals("SELECT ntile(2) OVER (PARTITION BY t.c0 ORDER BY t.c0) AS value FROM t0 AS t",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectValueFrom(table, ntile)));
        assertEquals(
                "SELECT nth_value(t.c0, 2) OVER "
                        + "(PARTITION BY CASE WHEN FALSE THEN t.c0 ELSE t.c0 END ORDER BY t.c0) AS value FROM t0 AS t",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectValueFrom(table, nthValue)));
    }

    @Test
    void floatingPointRankingWindowFunctionsRenderWithoutArguments() {
        GaussDBExpression expression = new GaussDBColumnReference(column, "t");
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);

        GaussDBWindowFunction percentRank = new GaussDBWindowFunction("percent_rank", List.of(),
                List.of(GaussDBEETOracle.equivalentExpression(expression, condition)), List.of(expression),
                GaussDBDataType.FLOAT);
        GaussDBWindowFunction cumeDist = new GaussDBWindowFunction("cume_dist", List.of(), List.of(expression),
                List.of(expression), GaussDBDataType.FLOAT);

        assertEquals(
                "SELECT percent_rank() OVER "
                        + "(PARTITION BY CASE WHEN FALSE THEN t.c0 ELSE t.c0 END ORDER BY t.c0) AS value FROM t0 AS t",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectValueFrom(table, percentRank)));
        assertEquals("SELECT cume_dist() OVER (PARTITION BY t.c0 ORDER BY t.c0) AS value FROM t0 AS t",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectValueFrom(table, cumeDist)));
    }

    @Test
    void framedWindowFunctionRendersFrameBoundaries() {
        GaussDBExpression expression = new GaussDBColumnReference(column, "t");
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);
        GaussDBWindowFrame rowsFrame = GaussDBWindowFrame.between(GaussDBWindowFrame.FrameType.ROWS,
                GaussDBWindowFrame.Boundary.preceding(GaussDBConstant.createIntConstant(1)),
                GaussDBWindowFrame.Boundary.currentRow());
        GaussDBWindowFrame rangeFrame = GaussDBWindowFrame.between(GaussDBWindowFrame.FrameType.RANGE,
                GaussDBWindowFrame.Boundary.unboundedPreceding(), GaussDBWindowFrame.Boundary.currentRow());

        GaussDBWindowFunction rowsFunction = new GaussDBWindowFunction("first_value", List.of(expression),
                List.of(GaussDBEETOracle.equivalentExpression(expression, condition)), List.of(expression),
                GaussDBDataType.INT, rowsFrame);
        GaussDBWindowFunction rangeFunction = new GaussDBWindowFunction("last_value", List.of(expression),
                List.of(expression), List.of(expression), GaussDBDataType.INT, rangeFrame);

        assertEquals(
                "SELECT first_value(t.c0) OVER (PARTITION BY CASE WHEN FALSE THEN t.c0 ELSE t.c0 END "
                        + "ORDER BY t.c0 ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS value FROM t0 AS t",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectValueFrom(table, rowsFunction)));
        assertEquals(
                "SELECT last_value(t.c0) OVER (PARTITION BY t.c0 ORDER BY t.c0 "
                        + "RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS value FROM t0 AS t",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectValueFrom(table, rangeFunction)));
    }

    @Test
    void filteredWindowFunctionRendersFilterAsCaseExpressionInsideAggregate() {
        GaussDBExpression expression = new GaussDBColumnReference(column, "t");
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);
        GaussDBExpression predicate = GaussDBConstant.createBooleanConstant(true);

        GaussDBWindowFunction windowFunction = new GaussDBWindowFunction("count", List.of(new GaussDBStar()),
                List.of(expression), List.of(expression), GaussDBDataType.INT, null,
                GaussDBEETOracle.equivalentExpression(predicate, condition));

        assertEquals(
                "SELECT count(CASE WHEN CASE WHEN FALSE THEN TRUE ELSE TRUE END THEN 1 END) "
                        + "OVER (PARTITION BY t.c0 ORDER BY t.c0) AS value FROM t0 AS t",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectValueFrom(table, windowFunction)));
    }

    @Test
    void recursiveCteQueryRendersAnchorAndRecursiveTerm() {
        GaussDBColumn valueColumn = new GaussDBColumn("value", GaussDBDataType.INT);
        GaussDBExpression predicate = new sqlancer.gaussdb.ast.GaussDBBinaryOperation(
                new GaussDBColumnReference(valueColumn, "r"), GaussDBConstant.createIntConstant(3),
                sqlancer.gaussdb.ast.GaussDBBinaryOperation.Operator.LESS, GaussDBDataType.BOOLEAN);

        assertEquals(
                "WITH RECURSIVE eet_recursive_cte AS ((SELECT 1 AS value) UNION ALL "
                        + "(SELECT ((r.value) + (1)) AS value FROM eet_recursive_cte AS r "
                        + "WHERE ((r.value) < (3)))) SELECT * FROM eet_recursive_cte AS o",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarFromRecursiveCte(predicate)));
    }

    @Test
    void multipleCtesQueryRendersIndependentCteDefinitions() {
        GaussDBExpression firstPredicate = GaussDBConstant.createBooleanConstant(true);
        GaussDBExpression secondPredicate = GaussDBConstant.createBooleanConstant(false);
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);

        assertEquals(
                "WITH eet_cte0 AS ((SELECT * FROM t0 AS t WHERE TRUE)), "
                        + "eet_cte1 AS ((SELECT * FROM t0 AS t WHERE FALSE)) "
                        + "SELECT * FROM ((SELECT * FROM eet_cte0 AS eet_cte0) "
                        + "UNION ALL (SELECT * FROM eet_cte1 AS eet_cte1)) AS q",
                GaussDBToStringVisitor
                        .asString(GaussDBEETOracle.selectStarFromMultipleCtes(table, firstPredicate, secondPredicate)));
        assertEquals(
                "WITH eet_cte0 AS ((SELECT * FROM t0 AS t WHERE CASE WHEN FALSE THEN TRUE ELSE TRUE END)), "
                        + "eet_cte1 AS ((SELECT * FROM t0 AS t WHERE CASE WHEN FALSE THEN FALSE ELSE FALSE END)) "
                        + "SELECT * FROM ((SELECT * FROM eet_cte0 AS eet_cte0) "
                        + "UNION ALL (SELECT * FROM eet_cte1 AS eet_cte1)) AS q",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarFromMultipleCtes(table,
                        GaussDBEETOracle.equivalentExpression(firstPredicate, condition),
                        GaussDBEETOracle.equivalentExpression(secondPredicate, condition))));
    }

    @Test
    void orderByExpressionQueryUsesDeterministicSecondaryKey() {
        GaussDBExpression expression = new GaussDBColumnReference(column, "t");
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);
        GaussDBExpression ctid = new GaussDBColumnReference(new GaussDBColumn("ctid", GaussDBDataType.TEXT), "t");

        GaussDBSelect select = GaussDBEETOracle.selectStarWhere(table, GaussDBConstant.createBooleanConstant(true));
        select.setOrderByClauses(List.of(GaussDBEETOracle.equivalentExpression(expression, condition), ctid));

        assertEquals("SELECT * FROM t0 AS t WHERE TRUE " + "ORDER BY CASE WHEN FALSE THEN t.c0 ELSE t.c0 END, t.ctid",
                GaussDBToStringVisitor.asString(select));
    }

    @Test
    void multiColumnOrderByQueryUsesDeterministicFinalKey() {
        GaussDBColumn secondColumn = new GaussDBColumn("c1", GaussDBDataType.TEXT);
        GaussDBTable twoColumnTable = new GaussDBTable("t0", List.of(column, secondColumn), null, false, true);
        GaussDBExpression first = new GaussDBColumnReference(column, "t");
        GaussDBExpression second = new GaussDBColumnReference(secondColumn, "t");
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);
        GaussDBExpression ctid = new GaussDBColumnReference(new GaussDBColumn("ctid", GaussDBDataType.TEXT), "t");

        GaussDBSelect select = GaussDBEETOracle.selectStarWhere(twoColumnTable,
                GaussDBConstant.createBooleanConstant(true));
        select.setOrderByClauses(List.of(GaussDBEETOracle.equivalentExpression(first, condition), second, ctid));

        assertEquals(
                "SELECT * FROM t0 AS t WHERE TRUE " + "ORDER BY CASE WHEN FALSE THEN t.c0 ELSE t.c0 END, t.c1, t.ctid",
                GaussDBToStringVisitor.asString(select));
    }

    @Test
    void setOperationQueryTransformsLeftPredicate() {
        GaussDBExpression leftPredicate = GaussDBConstant.createBooleanConstant(true);
        GaussDBExpression rightPredicate = GaussDBConstant.createBooleanConstant(false);
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);

        GaussDBSelect left = GaussDBEETOracle.selectStarWhere(table,
                GaussDBEETOracle.equivalentExpression(leftPredicate, condition));
        GaussDBSelect right = GaussDBEETOracle.selectStarWhere(table, rightPredicate);
        GaussDBSetOperation setOperation = new GaussDBSetOperation(left, right, GaussDBSetOperation.Operator.UNION_ALL);

        assertEquals(
                "(SELECT * FROM t0 AS t WHERE CASE WHEN FALSE THEN TRUE ELSE TRUE END) "
                        + "UNION ALL (SELECT * FROM t0 AS t WHERE FALSE)",
                GaussDBToStringVisitor.asString(setOperation));
    }

    @Test
    void limitExpressionQueryTransformsLimit() {
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);
        GaussDBExpression ctid = new GaussDBColumnReference(new GaussDBColumn("ctid", GaussDBDataType.TEXT), "t");

        GaussDBSelect select = GaussDBEETOracle.selectStarWhere(table, GaussDBConstant.createBooleanConstant(true));
        select.setOrderByClauses(List.of(ctid));
        select.setLimitClause(GaussDBEETOracle.equivalentExpression(GaussDBConstant.createIntConstant(1), condition));

        assertEquals("SELECT * FROM t0 AS t WHERE TRUE ORDER BY t.ctid " + "LIMIT CASE WHEN FALSE THEN 1 ELSE 1 END",
                GaussDBToStringVisitor.asString(select));
    }

    @Test
    void offsetExpressionQueryTransformsOffset() {
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);
        GaussDBExpression ctid = new GaussDBColumnReference(new GaussDBColumn("ctid", GaussDBDataType.TEXT), "t");

        GaussDBSelect select = GaussDBEETOracle.selectStarWhere(table, GaussDBConstant.createBooleanConstant(true));
        select.setOrderByClauses(List.of(ctid));
        select.setLimitClause(GaussDBConstant.createIntConstant(1000));
        select.setOffsetClause(GaussDBEETOracle.equivalentExpression(GaussDBConstant.createIntConstant(1), condition));

        assertEquals("SELECT * FROM t0 AS t WHERE TRUE ORDER BY t.ctid LIMIT 1000 "
                + "OFFSET CASE WHEN FALSE THEN 1 ELSE 1 END", GaussDBToStringVisitor.asString(select));
    }

    @Test
    void selectListScalarSubqueryQueryUsesCorrelatedInnerSelect() {
        GaussDBSelect innerSelect = new GaussDBSelect();
        innerSelect.setFetchColumns(List.of(new GaussDBAlias(new GaussDBColumnReference(column, "i"), "value")));
        innerSelect.setFromList(List.of(new GaussDBAlias(new GaussDBTableReference(table), "i")));
        innerSelect.setWhereClause(new sqlancer.gaussdb.ast.GaussDBBinaryOperation(
                new GaussDBColumnReference(column, "i"), new GaussDBColumnReference(column, "t"),
                sqlancer.gaussdb.ast.GaussDBBinaryOperation.Operator.EQUALS, GaussDBDataType.BOOLEAN));
        innerSelect.setOrderByClauses(
                List.of(new GaussDBColumnReference(new GaussDBColumn("ctid", GaussDBDataType.TEXT), "i")));
        innerSelect.setLimitClause(GaussDBConstant.createIntConstant(1));

        GaussDBExpression subquery = new GaussDBSubquery(innerSelect);

        assertEquals(
                "SELECT (SELECT i.c0 AS value FROM t0 AS i WHERE ((i.c0) = (t.c0)) "
                        + "ORDER BY i.ctid LIMIT 1) AS value FROM t0 AS t",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectValueFrom(table, subquery)));
    }

    @Test
    void selectListExistsQueryUsesCorrelatedInnerSelect() {
        GaussDBSelect innerSelect = new GaussDBSelect();
        innerSelect.setFetchColumns(List.of(GaussDBConstant.createIntConstant(1)));
        innerSelect.setFromList(List.of(new GaussDBAlias(new GaussDBTableReference(table), "i")));
        innerSelect.setWhereClause(new sqlancer.gaussdb.ast.GaussDBBinaryOperation(
                new GaussDBColumnReference(column, "i"), new GaussDBColumnReference(column, "t"),
                sqlancer.gaussdb.ast.GaussDBBinaryOperation.Operator.EQUALS, GaussDBDataType.BOOLEAN));

        GaussDBExpression exists = new GaussDBExists(new GaussDBSubquery(innerSelect), false);

        assertEquals("SELECT EXISTS (SELECT 1 FROM t0 AS i WHERE ((i.c0) = (t.c0))) AS value FROM t0 AS t",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectValueFrom(table, exists)));
    }

    @Test
    void aggregateArgumentQueryTransformsArgument() {
        GaussDBExpression condition = GaussDBConstant.createBooleanConstant(false);
        GaussDBAggregate aggregate = new GaussDBAggregate(GaussDBAggregate.Function.SUM,
                List.of(GaussDBEETOracle.equivalentExpression(new GaussDBColumnReference(column, "t"), condition)),
                GaussDBDataType.INT);

        assertEquals("SELECT SUM(CASE WHEN FALSE THEN t.c0 ELSE t.c0 END) AS row_count FROM t0 AS t",
                GaussDBToStringVisitor.asString(GaussDBEETOracle.selectAggregateFrom(table, aggregate)));
    }

}
