package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Reproducer;
import sqlancer.SQLConnection;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.gaussdb.GaussDBErrors;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.GaussDBToStringVisitor;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.gen.GaussDBBoundaryHelper;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

/**
 * Built-in SQL Function Boundary Fuzzing (EuroSys'25) Oracle for GaussDB.
 *
 * <p>
 * Injects extreme boundary arguments (NaN, Infinity, 0, INT_MIN/MAX, empty strings, format masks, leap dates)
 * into GaussDB Oracle-compatibility A-mode and PostgreSQL built-in functions, and cross-checks execution across
 * direct evaluation, CTE materialization, and derived table subquery forms.
 * </p>
 */
public class GaussDBBoundaryOracle implements TestOracle<GaussDBGlobalState> {

    private static final String TABLE_ALIAS = "t";

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBBoundaryReproducer reproducer;
    private String lastQueryString;

    public GaussDBBoundaryOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public static final class BoundaryBundle {

        private final String directQuery;
        private final String cteQuery;
        private final String subqueryQuery;

        public BoundaryBundle(String directQuery, String cteQuery, String subqueryQuery) {
            this.directQuery = directQuery;
            this.cteQuery = cteQuery;
            this.subqueryQuery = subqueryQuery;
        }

        public String getDirectQuery() {
            return directQuery;
        }

        public String getCteQuery() {
            return cteQuery;
        }

        public String getSubqueryQuery() {
            return subqueryQuery;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());

        GaussDBDataType returnType = GaussDBDataType.getRandomType();
        List<GaussDBExpression> candidateArgs = table.getColumns().stream()
                .map(col -> new GaussDBColumnReference(col, TABLE_ALIAS))
                .collect(Collectors.toList());

        GaussDBExpression functionExpr = GaussDBBoundaryHelper.generateBoundaryFunction(
                state.getRandomly(), returnType, candidateArgs);

        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(table.getColumns());
        generator.setTableAlias(TABLE_ALIAS);
        GaussDBExpression wherePredicate = generator.generateExpression(GaussDBDataType.BOOLEAN);

        BoundaryBundle bundle = buildBoundaryBundle(table, functionExpr, wherePredicate);
        verifyBoundaryBundle(bundle);
    }

    public static BoundaryBundle buildBoundaryBundle(GaussDBTable table, GaussDBExpression functionExpr,
            GaussDBExpression wherePredicate) {
        String funcStr = GaussDBToStringVisitor.asString(functionExpr);
        String predStr = GaussDBToStringVisitor.asString(wherePredicate);
        String tableName = table.getName();

        // Form 1: Direct SELECT
        String directSql = String.format("SELECT %s FROM %s %s WHERE %s",
                funcStr, tableName, TABLE_ALIAS, predStr);

        // Form 2: Common Table Expression (CTE)
        String cteSql = String.format("WITH cte AS (SELECT * FROM %s) SELECT %s FROM cte %s WHERE %s",
                tableName, funcStr, TABLE_ALIAS, predStr);

        // Form 3: Derived Subquery in FROM
        String subquerySql = String.format("SELECT sub.val FROM (SELECT %s AS val FROM %s %s WHERE %s) sub",
                funcStr, tableName, TABLE_ALIAS, predStr);

        return new BoundaryBundle(directSql, cteSql, subquerySql);
    }

    private void verifyBoundaryBundle(BoundaryBundle bundle) throws SQLException {
        this.lastQueryString = bundle.getDirectQuery();
        SQLConnection con = state.getConnection();

        try (Statement statement = con.createStatement()) {
            List<String> directRows;
            try {
                directRows = executeAndGetRows(statement, bundle.getDirectQuery());
            } catch (SQLException e) {
                if (errors.errorIsExpected(e.getMessage())) {
                    return;
                }
                throw e;
            }

            List<String> cteRows;
            try {
                cteRows = executeAndGetRows(statement, bundle.getCteQuery());
            } catch (SQLException e) {
                if (errors.errorIsExpected(e.getMessage())) {
                    return;
                }
                this.reproducer = new GaussDBBoundaryReproducer(bundle);
                throw new AssertionError(String.format(
                        "Boundary Function CTE Form failed while Direct succeeded!%nDirect Query: %s%nCTE Query: %s%nError: %s",
                        bundle.getDirectQuery(), bundle.getCteQuery(), e.getMessage()), e);
            }

            if (!areEquivalent(directRows, cteRows)) {
                this.reproducer = new GaussDBBoundaryReproducer(bundle);
                throw new AssertionError(String.format(
                        "Boundary Function Violation between Direct and CTE!%nDirect (%d rows): %s%nCTE (%d rows): %s%nQuery: %s",
                        directRows.size(), directRows, cteRows.size(), cteRows, bundle.getDirectQuery()));
            }

            List<String> subqueryRows;
            try {
                subqueryRows = executeAndGetRows(statement, bundle.getSubqueryQuery());
            } catch (SQLException e) {
                if (errors.errorIsExpected(e.getMessage())) {
                    return;
                }
                this.reproducer = new GaussDBBoundaryReproducer(bundle);
                throw new AssertionError(String.format(
                        "Boundary Function Subquery Form failed while Direct succeeded!%nDirect Query: %s%nSubquery: %s%nError: %s",
                        bundle.getDirectQuery(), bundle.getSubqueryQuery(), e.getMessage()), e);
            }

            if (!areEquivalent(directRows, subqueryRows)) {
                this.reproducer = new GaussDBBoundaryReproducer(bundle);
                throw new AssertionError(String.format(
                        "Boundary Function Violation between Direct and Subquery!%nDirect (%d rows): %s%nSubquery (%d rows): %s%nQuery: %s",
                        directRows.size(), directRows, subqueryRows.size(), subqueryRows, bundle.getDirectQuery()));
            }
        }
    }

    public static boolean areEquivalent(List<String> list1, List<String> list2) {
        if (list1.size() != list2.size()) {
            return false;
        }
        List<String> sorted1 = new ArrayList<>(list1);
        List<String> sorted2 = new ArrayList<>(list2);
        sorted1.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
        sorted2.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
        return sorted1.equals(sorted2);
    }

    private List<String> executeAndGetRows(Statement statement, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>(cols);
                for (int i = 1; i <= cols; i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row.toString());
            }
        }
        return rows;
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
