package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

/**
 * Differential Query Execution (DQE, ICSE'23) oracle for GaussDB.
 *
 * <p>
 * Verifies that the same {@code WHERE} predicate selects the exact same set of rows across {@code SELECT},
 * {@code UPDATE ... RETURNING}, and {@code DELETE ... RETURNING} on both {@code astore} and {@code ustore} tables.
 * </p>
 */
public class GaussDBDQEOracle implements TestOracle<GaussDBGlobalState> {

    static final String WORK_SUFFIX = "_dqe_work";
    static final String ROW_ID_COL = "dqe_rid";
    static final String UPDATED_COL = "dqe_updated";
    private static final String TABLE_ALIAS = "t";

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private final Set<String> initializedTables = new HashSet<>();
    private GaussDBDQEReproducer reproducer;
    private String lastQueryString;

    public GaussDBDQEOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public static final class DQEQueryPlan {

        private final List<String> setupSql;
        private final String selectSql;
        private final String updateSql;
        private final String selectUpdatedSql;
        private final String deleteSql;
        private final String cleanupSql;

        DQEQueryPlan(List<String> setupSql, String selectSql, String updateSql, String selectUpdatedSql,
                String deleteSql, String cleanupSql) {
            this.setupSql = Collections.unmodifiableList(new ArrayList<>(setupSql));
            this.selectSql = selectSql;
            this.updateSql = updateSql;
            this.selectUpdatedSql = selectUpdatedSql;
            this.deleteSql = deleteSql;
            this.cleanupSql = cleanupSql;
        }

        public List<String> getSetupSql() {
            return setupSql;
        }

        public String getSelectSql() {
            return selectSql;
        }

        public String getUpdateSql() {
            return updateSql;
        }

        public String getSelectUpdatedSql() {
            return selectUpdatedSql;
        }

        public String getDeleteSql() {
            return deleteSql;
        }

        public String getCleanupSql() {
            return cleanupSql;
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
        GaussDBExpression predicate = generator.generateExpression(GaussDBDataType.BOOLEAN);
        String storageType = Randomly.fromOptions("astore", "ustore");
        GaussDBColumn indexedCol = Randomly.getBoolean() ? Randomly.fromList(table.getColumns()) : null;

        DQEQueryPlan plan = createPlan(table, predicate, storageType, indexedCol);
        verifyPlan(table.getName(), plan);
    }

    static DQEQueryPlan createPlan(GaussDBTable table, GaussDBExpression predicate, String storageType,
            GaussDBColumn indexedColumn) {
        String baseName = table.getName();
        String workTable = baseName + WORK_SUFFIX;
        String predicateSql = GaussDBToStringVisitor.asString(predicate);

        List<String> setup = new ArrayList<>();
        setup.add("DROP TABLE IF EXISTS " + workTable + " CASCADE");
        setup.add("CREATE TABLE " + workTable + " WITH (storage_type = " + storageType
                + ") AS SELECT row_number() OVER () AS " + ROW_ID_COL + ", 0::INT AS " + UPDATED_COL + ", * FROM "
                + baseName);
        if (indexedColumn != null) {
            String indexMethod = "ustore".equalsIgnoreCase(storageType) ? "ubtree" : "btree";
            setup.add("CREATE INDEX " + workTable + "_idx ON " + workTable + " USING " + indexMethod + " ("
                    + indexedColumn.getName() + ")");
        }

        String selectSql = "SELECT " + ROW_ID_COL + " FROM " + workTable + " AS " + TABLE_ALIAS + " WHERE "
                + predicateSql;
        String updateSql = "UPDATE " + workTable + " AS " + TABLE_ALIAS + " SET " + UPDATED_COL + " = 1 WHERE "
                + predicateSql + " RETURNING " + ROW_ID_COL;
        String selectUpdatedSql = "SELECT " + ROW_ID_COL + " FROM " + workTable + " WHERE " + UPDATED_COL + " = 1";
        String deleteSql = "DELETE FROM " + workTable + " AS " + TABLE_ALIAS + " WHERE " + predicateSql + " RETURNING "
                + ROW_ID_COL;
        String cleanupSql = "DROP TABLE IF EXISTS " + workTable + " CASCADE";
        return new DQEQueryPlan(setup, selectSql, updateSql, selectUpdatedSql, deleteSql, cleanupSql);
    }

    private void verifyPlan(String baseTableName, DQEQueryPlan plan) throws SQLException {
        lastQueryString = plan.getSelectSql();
        SQLConnection connection = state.getConnection();
        try (Statement statement = connection.createStatement()) {
            if (!initializedTables.contains(baseTableName)) {
                for (String sql : plan.getSetupSql()) {
                    executeSetup(statement, sql, errors);
                }
                initializedTables.add(baseTableName);
            }
            if (runTransactionalCheck(statement, plan, errors)) {
                return;
            }
            // Re-run once from a fresh snapshot to rule out nondeterministic predicates.
            if (!runAndCheck(statement, plan)) {
                reproducer = new GaussDBDQEReproducer(plan);
                throw new AssertionError("DQE predicate mismatch across SELECT / UPDATE / DELETE\nSelect: "
                        + plan.getSelectSql() + "\nUpdate: " + plan.getUpdateSql() + "\nDelete: "
                        + plan.getDeleteSql());
            }
        }
    }

    private static boolean runTransactionalCheck(Statement statement, DQEQueryPlan plan, ExpectedErrors errors)
            throws SQLException {
        statement.execute("BEGIN");
        try {
            List<String> selectRows = executeAndGetRows(statement, plan.getSelectSql(), errors);
            List<String> updateReturningRows = executeAndGetRows(statement, plan.getUpdateSql(), errors);
            List<String> updatedRows = executeAndGetRows(statement, plan.getSelectUpdatedSql(), errors);
            List<String> deleteReturningRows = executeAndGetRows(statement, plan.getDeleteSql(), errors);
            return areEquivalent(selectRows, updateReturningRows) && areEquivalent(selectRows, updatedRows)
                    && areEquivalent(selectRows, deleteReturningRows);
        } finally {
            quietlyExecute(statement, "ROLLBACK");
        }
    }

    static boolean runAndCheck(Statement statement, DQEQueryPlan plan, ExpectedErrors errors) throws SQLException {
        for (String sql : plan.getSetupSql()) {
            executeSetup(statement, sql, errors);
        }
        List<String> selectRows = executeAndGetRows(statement, plan.getSelectSql(), errors);
        List<String> updateReturningRows = executeAndGetRows(statement, plan.getUpdateSql(), errors);
        List<String> updatedRows = executeAndGetRows(statement, plan.getSelectUpdatedSql(), errors);
        List<String> deleteReturningRows = executeAndGetRows(statement, plan.getDeleteSql(), errors);
        return areEquivalent(selectRows, updateReturningRows) && areEquivalent(selectRows, updatedRows)
                && areEquivalent(selectRows, deleteReturningRows);
    }

    private boolean runAndCheck(Statement statement, DQEQueryPlan plan) throws SQLException {
        return runAndCheck(statement, plan, errors);
    }

    private static void executeSetup(Statement statement, String sql, ExpectedErrors errors) throws SQLException {
        try {
            statement.execute(sql);
        } catch (SQLException e) {
            if (errors != null && errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        }
    }

    private static List<String> executeAndGetRows(Statement statement, String query, ExpectedErrors errors)
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

    private static void quietlyExecute(Statement statement, String sql) {
        try {
            statement.execute(sql);
        } catch (SQLException ignored) {
            // Best-effort cleanup.
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
