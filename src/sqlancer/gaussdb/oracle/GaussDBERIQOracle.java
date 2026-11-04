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
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBSelect;
import sqlancer.gaussdb.ast.GaussDBStar;
import sqlancer.gaussdb.ast.GaussDBTableReference;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

/**
 * Equivalent Representation of Intermediate Queries (ERIQ, arXiv'26) oracle for GaussDB.
 *
 * <p>
 * Expresses an identical intermediate query via canonical representations:
 * 1. Inline Derived Table (subquery in FROM)
 * 2. Common Table Expression (WITH cte AS ...)
 * 3. Materialized View (CREATE VIEW v AS ...)
 * 4. Temporary Table (CREATE TEMPORARY TABLE t AS ...)
 *
 * Then applies an identical outer query (filters, projections, aggregations) and asserts result equivalence.
 * Divergences uncover deep optimizer bugs in subquery pullup, CTE inlining/materialization, and view expansion.
 * </p>
 */
public class GaussDBERIQOracle implements TestOracle<GaussDBGlobalState> {

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBERIQReproducer reproducer;

    public GaussDBERIQOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public static final class ERIQQueryBundle {

        private final String derivedQuery;
        private final String cteQuery;
        private final String viewSetup;
        private final String viewQuery;
        private final String viewCleanup;
        private final String tempSetup;
        private final String tempQuery;
        private final String tempCleanup;

        public ERIQQueryBundle(String derivedQuery, String cteQuery, String viewSetup, String viewQuery,
                String viewCleanup, String tempSetup, String tempQuery, String tempCleanup) {
            this.derivedQuery = derivedQuery;
            this.cteQuery = cteQuery;
            this.viewSetup = viewSetup;
            this.viewQuery = viewQuery;
            this.viewCleanup = viewCleanup;
            this.tempSetup = tempSetup;
            this.tempQuery = tempQuery;
            this.tempCleanup = tempCleanup;
        }

        public String getDerivedQuery() {
            return derivedQuery;
        }

        public String getCteQuery() {
            return cteQuery;
        }

        public String getViewSetup() {
            return viewSetup;
        }

        public String getViewQuery() {
            return viewQuery;
        }

        public String getViewCleanup() {
            return viewCleanup;
        }

        public String getTempSetup() {
            return tempSetup;
        }

        public String getTempQuery() {
            return tempQuery;
        }

        public String getTempCleanup() {
            return tempCleanup;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());

        ERIQQueryBundle bundle = buildQueryBundle(table);
        if (bundle == null) {
            return;
        }
        verifyBundle(bundle);
    }

    private ERIQQueryBundle buildQueryBundle(GaussDBTable table) {
        long threadId = Thread.currentThread().getId();
        String viewName = "eriq_v_" + threadId;
        String tempName = "eriq_tmp_" + threadId;

        // 1. Inner query: pick columns and inner WHERE filter
        GaussDBExpressionGenerator innerGen = new GaussDBExpressionGenerator(state);
        innerGen.setColumns(table.getColumns());
        innerGen.setTableAlias("b");
        GaussDBExpression innerPredicate = innerGen.generateExpression(GaussDBDataType.BOOLEAN);

        GaussDBSelect innerSelect = new GaussDBSelect();
        innerSelect.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), "b")));
        innerSelect.setWhereClause(innerPredicate);
        List<GaussDBExpression> innerCols = table.getColumns().stream()
                .map(c -> new GaussDBColumnReference(c, "b"))
                .collect(Collectors.toList());
        innerSelect.setFetchColumns(innerCols);
        String innerSql = GaussDBToStringVisitor.asString(innerSelect);

        // 2. Outer query generator over the intermediate table schema
        GaussDBTable intermediateTable = new GaussDBTable("sub", table.getColumns(), null, false, false);
        GaussDBExpressionGenerator outerGen = new GaussDBExpressionGenerator(state);
        outerGen.setColumns(intermediateTable.getColumns());
        outerGen.setTableAlias("sub");
        GaussDBExpression outerPredicate = outerGen.generateExpression(GaussDBDataType.BOOLEAN);
        String outerPredSql = GaussDBToStringVisitor.asString(outerPredicate);

        // Variant outer projections
        int mode = Randomly.fromOptions(0, 1, 2);
        String derivedSql;
        String cteSql;
        String viewQuery;
        String tempQuery;

        if (mode == 0) {
            // SELECT * FROM ... WHERE outer_predicate
            derivedSql = "SELECT * FROM (" + innerSql + ") AS sub WHERE " + outerPredSql;
            cteSql = "WITH sub AS (" + innerSql + ") SELECT * FROM sub WHERE " + outerPredSql;
            viewQuery = "SELECT * FROM " + viewName + " AS sub WHERE " + outerPredSql;
            tempQuery = "SELECT * FROM " + tempName + " AS sub WHERE " + outerPredSql;
        } else if (mode == 1) {
            // SELECT count(*), min(col), max(col) FROM ... WHERE outer_predicate
            GaussDBColumn numCol = intermediateTable.getColumns().get(0);
            derivedSql = "SELECT count(*), min(sub." + numCol.getName() + ") FROM (" + innerSql + ") AS sub WHERE "
                    + outerPredSql;
            cteSql = "WITH sub AS (" + innerSql + ") SELECT count(*), min(sub." + numCol.getName()
                    + ") FROM sub WHERE " + outerPredSql;
            viewQuery = "SELECT count(*), min(sub." + numCol.getName() + ") FROM " + viewName + " AS sub WHERE "
                    + outerPredSql;
            tempQuery = "SELECT count(*), min(sub." + numCol.getName() + ") FROM " + tempName + " AS sub WHERE "
                    + outerPredSql;
        } else {
            // SELECT DISTINCT sub.col FROM ... WHERE outer_predicate
            GaussDBColumn pickCol = Randomly.fromList(intermediateTable.getColumns());
            derivedSql = "SELECT DISTINCT sub." + pickCol.getName() + " FROM (" + innerSql + ") AS sub WHERE "
                    + outerPredSql;
            cteSql = "WITH sub AS (" + innerSql + ") SELECT DISTINCT sub." + pickCol.getName() + " FROM sub WHERE "
                    + outerPredSql;
            viewQuery = "SELECT DISTINCT sub." + pickCol.getName() + " FROM " + viewName + " AS sub WHERE "
                    + outerPredSql;
            tempQuery = "SELECT DISTINCT sub." + pickCol.getName() + " FROM " + tempName + " AS sub WHERE "
                    + outerPredSql;
        }

        String viewSetup = "CREATE OR REPLACE VIEW " + viewName + " AS " + innerSql;
        String viewCleanup = "DROP VIEW IF EXISTS " + viewName + " CASCADE";
        String tempSetup = "CREATE TEMPORARY TABLE " + tempName + " AS " + innerSql;
        String tempCleanup = "DROP TABLE IF EXISTS " + tempName + " CASCADE";

        return new ERIQQueryBundle(derivedSql, cteSql, viewSetup, viewQuery, viewCleanup, tempSetup, tempQuery,
                tempCleanup);
    }

    private void verifyBundle(ERIQQueryBundle bundle) throws SQLException {
        SQLConnection connection = state.getConnection();
        try (Statement statement = connection.createStatement()) {
            List<String> derivedResults = executeAndGetRows(statement, bundle.getDerivedQuery());
            List<String> cteResults = executeAndGetRows(statement, bundle.getCteQuery());

            if (!areEquivalent(derivedResults, cteResults)) {
                // Re-run once to exclude non-deterministic expressions
                derivedResults = executeAndGetRows(statement, bundle.getDerivedQuery());
                cteResults = executeAndGetRows(statement, bundle.getCteQuery());
                if (!areEquivalent(derivedResults, cteResults)) {
                    reproducer = new GaussDBERIQReproducer(bundle);
                    throw new AssertionError("ERIQ intermediate representation mismatch (Derived Table vs CTE)\n"
                            + "DerivedQuery: " + bundle.getDerivedQuery() + "\n"
                            + "CTEQuery: " + bundle.getCteQuery() + "\n"
                            + "DerivedResult: " + derivedResults + "\n"
                            + "CTEResult: " + cteResults);
                }
            }

            // Check against VIEW representation
            if (bundle.getViewSetup() != null) {
                try {
                    try {
                        statement.execute(bundle.getViewCleanup());
                    } catch (SQLException ignored) {
                    }
                    executeSetup(statement, bundle.getViewSetup());
                    List<String> viewResults = executeAndGetRows(statement, bundle.getViewQuery());
                    if (!areEquivalent(derivedResults, viewResults)) {
                        derivedResults = executeAndGetRows(statement, bundle.getDerivedQuery());
                        viewResults = executeAndGetRows(statement, bundle.getViewQuery());
                        if (!areEquivalent(derivedResults, viewResults)) {
                            reproducer = new GaussDBERIQReproducer(bundle);
                            throw new AssertionError("ERIQ intermediate representation mismatch (Derived Table vs VIEW)\n"
                                    + "DerivedQuery: " + bundle.getDerivedQuery() + "\n"
                                    + "ViewSetup: " + bundle.getViewSetup() + "\n"
                                    + "ViewQuery: " + bundle.getViewQuery() + "\n"
                                    + "DerivedResult: " + derivedResults + "\n"
                                    + "ViewResult: " + viewResults);
                        }
                    }
                } finally {
                    try {
                        statement.execute(bundle.getViewCleanup());
                    } catch (SQLException ignored) {
                    }
                }
            }

            // Check against TEMPORARY TABLE representation
            if (bundle.getTempSetup() != null) {
                try {
                    try {
                        statement.execute(bundle.getTempCleanup());
                    } catch (SQLException ignored) {
                    }
                    executeSetup(statement, bundle.getTempSetup());
                    List<String> tempResults = executeAndGetRows(statement, bundle.getTempQuery());
                    if (!areEquivalent(derivedResults, tempResults)) {
                        derivedResults = executeAndGetRows(statement, bundle.getDerivedQuery());
                        tempResults = executeAndGetRows(statement, bundle.getTempQuery());
                        if (!areEquivalent(derivedResults, tempResults)) {
                            reproducer = new GaussDBERIQReproducer(bundle);
                            throw new AssertionError("ERIQ intermediate representation mismatch (Derived Table vs TempTable)\n"
                                    + "DerivedQuery: " + bundle.getDerivedQuery() + "\n"
                                    + "TempSetup: " + bundle.getTempSetup() + "\n"
                                    + "TempQuery: " + bundle.getTempQuery() + "\n"
                                    + "DerivedResult: " + derivedResults + "\n"
                                    + "TempResult: " + tempResults);
                        }
                    }
                } finally {
                    try {
                        statement.execute(bundle.getTempCleanup());
                    } catch (SQLException ignored) {
                    }
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

    public static boolean areEquivalent(List<String> list1, List<String> list2) {
        if (list1.size() != list2.size()) {
            return false;
        }
        List<String> sorted1 = new ArrayList<>(list1);
        List<String> sorted2 = new ArrayList<>(list2);
        sorted1.sort(Comparator.naturalOrder());
        sorted2.sort(Comparator.naturalOrder());
        return sorted1.equals(sorted2);
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
