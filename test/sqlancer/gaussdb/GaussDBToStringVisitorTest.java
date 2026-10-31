package sqlancer.gaussdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.ast.GaussDBAggregate;
import sqlancer.gaussdb.ast.GaussDBAlias;
import sqlancer.gaussdb.ast.GaussDBAnyAllOperation;
import sqlancer.gaussdb.ast.GaussDBBetweenOperation;
import sqlancer.gaussdb.ast.GaussDBBinaryOperation;
import sqlancer.gaussdb.ast.GaussDBCaseOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBExpressionBag;
import sqlancer.gaussdb.ast.GaussDBExtractOperation;
import sqlancer.gaussdb.ast.GaussDBInOperation;
import sqlancer.gaussdb.ast.GaussDBFunction;
import sqlancer.gaussdb.ast.GaussDBInSubquery;
import sqlancer.gaussdb.ast.GaussDBJoin;
import sqlancer.gaussdb.ast.GaussDBSelect;
import sqlancer.gaussdb.ast.GaussDBStar;
import sqlancer.gaussdb.ast.GaussDBSubquery;
import sqlancer.gaussdb.ast.GaussDBTableReference;
import sqlancer.gaussdb.ast.GaussDBValues;
import sqlancer.gaussdb.ast.GaussDBWithClause;

class GaussDBToStringVisitorTest {

    private final GaussDBColumn column = new GaussDBColumn("c0", GaussDBDataType.INT);
    private final GaussDBTable table = new GaussDBTable("t0", List.of(column), null, false, true);

    @Test
    void selectWithoutFromRendersScalarExpression() {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(List.of(new GaussDBAlias(GaussDBConstant.createBooleanConstant(true), "value")));
        select.setFromList(Collections.emptyList());

        assertEquals("SELECT TRUE AS value", GaussDBToStringVisitor.asString(select));
    }

    @Test
    void expressionBagRendersCurrentInnerExpression() {
        GaussDBExpressionBag expressionBag = new GaussDBExpressionBag(GaussDBConstant.createBooleanConstant(true));
        assertEquals("TRUE", GaussDBToStringVisitor.asString(expressionBag));

        expressionBag.updateInnerExpression(GaussDBConstant.createBooleanConstant(false));
        assertEquals("FALSE", GaussDBToStringVisitor.asString(expressionBag));
    }

    @Test
    void selectWithJoinRendersJoinClause() {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(List.of(new GaussDBStar()));
        select.setFromList(List.of(new GaussDBAlias(new GaussDBTableReference(table), "l")));
        select.setJoinClauses(List.of(new GaussDBJoin(
                new GaussDBAlias(new GaussDBTableReference(table), "l"),
                new GaussDBAlias(new GaussDBTableReference(table), "r"), GaussDBJoin.JoinType.INNER,
                new GaussDBBinaryOperation(new GaussDBColumnReference(column, "l"),
                        new GaussDBColumnReference(column, "r"), GaussDBBinaryOperation.Operator.GREATER,
                        GaussDBDataType.BOOLEAN))));

        assertEquals("SELECT * FROM t0 AS l JOIN t0 AS r ON ((l.c0) > (r.c0))",
                GaussDBToStringVisitor.asString(select));
    }

    @Test
    void cteRendersWithClause() {
        GaussDBSelect inner = new GaussDBSelect();
        inner.setFetchColumns(List.of(new GaussDBAlias(new GaussDBColumnReference(column, "s"), "col")));
        inner.setFromList(List.of(new GaussDBAlias(new GaussDBTableReference(table), "s")));

        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(List.of(new GaussDBStar()));
        select.setFromList(List.of(new GaussDBAlias(new GaussDBTableReference(table), "cte")));
        select.setWithClause(new GaussDBWithClause("cte(col)", new GaussDBSubquery(inner)));

        assertEquals("WITH cte(col) AS ((SELECT s.c0 AS col FROM t0 AS s)) SELECT * FROM t0 AS cte",
                GaussDBToStringVisitor.asString(select));
    }

    @Test
    void derivedTableRendersValues() {
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(List.of(new GaussDBStar()));
        select.setFromList(List.of(new GaussDBAlias(new GaussDBValues(List.of(GaussDBConstant.createIntConstant(1),
                GaussDBConstant.createIntConstant(2))), "sub(col)")));

        assertEquals("SELECT * FROM (VALUES (1), (2)) AS sub(col)", GaussDBToStringVisitor.asString(select));
    }

    @Test
    void derivedTableRendersValuesInsideExpressionBag() {
        GaussDBExpressionBag expressionBag = new GaussDBExpressionBag(
                new GaussDBValues(List.of(GaussDBConstant.createIntConstant(1))));
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(List.of(new GaussDBStar()));
        select.setFromList(List.of(new GaussDBAlias(expressionBag, "sub(col)")));

        assertEquals("SELECT * FROM (VALUES (1)) AS sub(col)", GaussDBToStringVisitor.asString(select));
    }

    @Test
    void functionRendersNameAndArguments() {
        GaussDBFunction function = new GaussDBFunction("concat",
                List.of(GaussDBConstant.createTextConstant("a"), GaussDBConstant.createTextConstant("b")),
                GaussDBDataType.TEXT);

        assertEquals("concat('a', 'b')", GaussDBToStringVisitor.asString(function));
    }

    @Test
    void temporalConstantsRenderSqlLiterals() {
        assertEquals("to_date('2020-01-01', 'YYYY-MM-DD')",
                GaussDBToStringVisitor.asString(GaussDBConstant.createDateConstant("2020-01-01")));
        assertEquals("TIMESTAMP '2020-01-01 12:30:45'",
                GaussDBToStringVisitor.asString(GaussDBConstant.createTimestampConstant("2020-01-01 12:30:45")));
        assertEquals("TIMESTAMPTZ '2020-01-01 12:30:45+08'",
                GaussDBToStringVisitor.asString(GaussDBConstant.createTimestamptzConstant("2020-01-01 12:30:45+08")));
        assertEquals("TIME '12:30:45'",
                GaussDBToStringVisitor.asString(GaussDBConstant.createTimeConstant("12:30:45")));
        assertEquals("INTERVAL '2 days 3 hours'",
                GaussDBToStringVisitor.asString(GaussDBConstant.createIntervalConstant("2 days 3 hours")));
    }

    @Test
    void temporalFunctionsRenderSql() {
        GaussDBFunction datePart = new GaussDBFunction("date_part",
                List.of(GaussDBConstant.createTextConstant("day"),
                        GaussDBConstant.createTimestampConstant("2020-01-02 03:04:05")),
                GaussDBDataType.DECIMAL);
        assertEquals("date_part('day', TIMESTAMP '2020-01-02 03:04:05')",
                GaussDBToStringVisitor.asString(datePart));

        GaussDBFunction justifyInterval = new GaussDBFunction("justify_interval",
                List.of(GaussDBConstant.createIntervalConstant("30 days 25 hours")),
                GaussDBDataType.INTERVAL);
        assertEquals("justify_interval(INTERVAL '30 days 25 hours')",
                GaussDBToStringVisitor.asString(justifyInterval));

        GaussDBExtractOperation extract = new GaussDBExtractOperation("day",
                GaussDBConstant.createTimestampConstant("2020-01-02 03:04:05"));
        assertEquals("EXTRACT(day FROM TIMESTAMP '2020-01-02 03:04:05')",
                GaussDBToStringVisitor.asString(extract));

        GaussDBExtractOperation extractMonth = new GaussDBExtractOperation("month",
                GaussDBConstant.createDateConstant("2020-01-02"));
        assertEquals("EXTRACT(month FROM to_date('2020-01-02', 'YYYY-MM-DD'))",
                GaussDBToStringVisitor.asString(extractMonth));

        GaussDBFunction datePartHour = new GaussDBFunction("date_part",
                List.of(GaussDBConstant.createTextConstant("hour"),
                        GaussDBConstant.createTimeConstant("12:34:56")),
                GaussDBDataType.DECIMAL);
        assertEquals("date_part('hour', TIME '12:34:56')",
                GaussDBToStringVisitor.asString(datePartHour));
    }

    @Test
    void temporalBetweenInAndCaseRenderSql() {
        GaussDBBetweenOperation between = new GaussDBBetweenOperation(
                GaussDBConstant.createDateConstant("2020-01-02"),
                GaussDBConstant.createDateConstant("2020-01-01"),
                GaussDBConstant.createDateConstant("2020-01-03"), false, false);
        assertEquals("(to_date('2020-01-02', 'YYYY-MM-DD')) BETWEEN "
                + "(to_date('2020-01-01', 'YYYY-MM-DD')) AND (to_date('2020-01-03', 'YYYY-MM-DD'))",
                GaussDBToStringVisitor.asString(between));

        GaussDBInOperation in = new GaussDBInOperation(
                GaussDBConstant.createTimestampConstant("2020-01-02 03:04:05"),
                List.of(GaussDBConstant.createTimestampConstant("2020-01-02 03:04:05"),
                        GaussDBConstant.createTimestampConstant("2020-01-03 03:04:05")),
                false);
        assertEquals("(TIMESTAMP '2020-01-02 03:04:05') IN "
                + "(TIMESTAMP '2020-01-02 03:04:05', TIMESTAMP '2020-01-03 03:04:05')",
                GaussDBToStringVisitor.asString(in));

        GaussDBCaseOperation caseOperation = new GaussDBCaseOperation(
                List.of(GaussDBConstant.createBooleanConstant(true)),
                List.of(GaussDBConstant.createIntervalConstant("2 days 3 hours")),
                GaussDBConstant.createIntervalConstant("1 day"), GaussDBDataType.INTERVAL);
        assertEquals("CASE WHEN TRUE THEN INTERVAL '2 days 3 hours' ELSE INTERVAL '1 day' END",
                GaussDBToStringVisitor.asString(caseOperation));
    }

    @Test
    void inSubqueryRendersInClause() {
        GaussDBSelect inner = new GaussDBSelect();
        inner.setFetchColumns(List.of(new GaussDBAlias(new GaussDBColumnReference(column, "s"), "col")));
        inner.setFromList(List.of(new GaussDBAlias(new GaussDBTableReference(table), "s")));

        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(List.of(new GaussDBStar()));
        select.setFromList(List.of(new GaussDBAlias(new GaussDBTableReference(table), "t")));
        select.setWhereClause(
                new GaussDBInSubquery(new GaussDBColumnReference(column, "t"), new GaussDBSubquery(inner), false));

        assertEquals("SELECT * FROM t0 AS t WHERE (t.c0) IN (SELECT s.c0 AS col FROM t0 AS s)",
                GaussDBToStringVisitor.asString(select));
    }

    @Test
    void anyAllRendersQuantifier() {
        GaussDBSelect inner = new GaussDBSelect();
        inner.setFetchColumns(List.of(new GaussDBAlias(new GaussDBColumnReference(column, "s"), "col")));
        inner.setFromList(List.of(new GaussDBAlias(new GaussDBTableReference(table), "s")));

        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(List.of(new GaussDBStar()));
        select.setFromList(List.of(new GaussDBAlias(new GaussDBTableReference(table), "t")));
        select.setWhereClause(new GaussDBAnyAllOperation(new GaussDBColumnReference(column, "t"),
                new GaussDBSubquery(inner), GaussDBBinaryOperation.Operator.GREATER,
                GaussDBAnyAllOperation.Quantifier.ANY));

        assertEquals("SELECT * FROM t0 AS t WHERE (t.c0  >  ANY (SELECT s.c0 AS col FROM t0 AS s))",
                GaussDBToStringVisitor.asString(select));
    }

    @Test
    void havingRendersGroupByAndHaving() {
        GaussDBSelect inner = new GaussDBSelect();
        inner.setFetchColumns(List.of(new GaussDBAlias(new GaussDBColumnReference(column, "s"), "col")));
        inner.setFromList(List.of(new GaussDBAlias(new GaussDBTableReference(table), "s")));

        GaussDBAggregate count = new GaussDBAggregate(GaussDBAggregate.Function.COUNT, List.of(new GaussDBStar()),
                GaussDBDataType.INT);
        GaussDBSelect select = new GaussDBSelect();
        select.setFetchColumns(List.of(new GaussDBAlias(new GaussDBColumnReference(column, "t"), "col"),
                new GaussDBAlias(count, "count")));
        select.setFromList(List.of(new GaussDBAlias(new GaussDBTableReference(table), "t")));
        select.setGroupByExpressions(List.of(new GaussDBColumnReference(column, "t")));
        select.setHavingClause(new GaussDBBinaryOperation(count, new GaussDBSubquery(inner),
                GaussDBBinaryOperation.Operator.GREATER, GaussDBDataType.BOOLEAN));

        assertEquals(
                "SELECT t.c0 AS col, COUNT(*) AS count FROM t0 AS t GROUP BY t.c0 HAVING ((COUNT(*)) > ((SELECT s.c0 AS col FROM t0 AS s)))",
                GaussDBToStringVisitor.asString(select));
    }

}
