package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.GaussDBToStringVisitor;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

/**
 * PUPPY (ICSE '25) Constrained Optimizer Space Exploration Oracle for GaussDB.
 *
 * <p>
 * Detects logic bugs and optimizer omissions by systematically disabling/constraining specific
 * optimizer plan paths (Hash Join, Merge Join, Index Scan, Seq Scan, Materialization) via session GUCs.
 *
 * Invariant: For any deterministic query Q, R_default(Q) &equiv; R_constrained(Q).
 * Disabling plan choices forces the CBO to explore fallback transformation paths, exposing latent
 * executor defects, incorrect scan filters, and cost-model crashes.
 * </p>
 */
public class GaussDBPuppyOracle implements TestOracle<GaussDBGlobalState> {

    public static final List<String> PUPPY_RESET_GUC_COMMANDS = Collections.unmodifiableList(Arrays.asList(
            "RESET enable_hashjoin;",
            "RESET enable_mergejoin;",
            "RESET enable_seqscan;",
            "RESET enable_indexscan;",
            "RESET enable_bitmapscan;",
            "RESET enable_material;",
            "RESET enable_sort;"
    ));

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBPuppyReproducer reproducer;

    public GaussDBPuppyOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public enum PuppyConstraintScenario {
        DISABLE_HASH_JOIN(Collections.singletonList("SET enable_hashjoin = off;")),
        FORCE_NESTLOOP(Arrays.asList("SET enable_hashjoin = off;", "SET enable_mergejoin = off;")),
        FORCE_INDEXSCAN(Collections.singletonList("SET enable_seqscan = off;")),
        FORCE_SEQSCAN(Arrays.asList("SET enable_indexscan = off;", "SET enable_bitmapscan = off;")),
        DISABLE_MATERIAL(Collections.singletonList("SET enable_material = off;")),
        DISABLE_SORT(Collections.singletonList("SET enable_sort = off;"));

        private final List<String> gucCommands;

        PuppyConstraintScenario(List<String> gucCommands) {
            this.gucCommands = gucCommands;
        }

        public List<String> getGucCommands() {
            return gucCommands;
        }
    }

    public static final class PuppyBundle {

        private final PuppyConstraintScenario scenario;
        private final String query;
        private final List<String> gucCommands;

        public PuppyBundle(PuppyConstraintScenario scenario, String query, List<String> gucCommands) {
            this.scenario = scenario;
            this.query = query;
            this.gucCommands = gucCommands;
        }

        public PuppyConstraintScenario getScenario() {
            return scenario;
        }

        public String getQuery() {
            return query;
        }

        public List<String> getGucCommands() {
            return gucCommands;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        List<GaussDBTable> allTables = state.getSchema().getDatabaseTables().stream()
                .filter(t -> !t.isView() && !t.getColumns().isEmpty())
                .collect(Collectors.toList());

        if (allTables.isEmpty()) {
            throw new IgnoreMeException();
        }

        GaussDBTable tableA = Randomly.fromList(allTables);
        GaussDBTable tableB = Randomly.fromList(allTables);

        GaussDBColumn colA = Randomly.fromList(tableA.getColumns());
        GaussDBColumn colB = Randomly.fromList(tableB.getColumns());

        boolean joinQuery = Randomly.getBoolean();
        GaussDBExpressionGenerator gen = new GaussDBExpressionGenerator(state).setColumns(tableA.getColumns());
        if (joinQuery) {
            gen.setTableAlias("a");
        }
        GaussDBExpression whereExpr = gen.generatePredicate();
        String whereSql = GaussDBToStringVisitor.asString(whereExpr);

        String query;
        if (joinQuery) {
            // Multi-table Join query
            query = "SELECT DISTINCT a." + colA.getName() + ", b." + colB.getName() + " FROM " + tableA.getName()
                    + " a JOIN " + tableB.getName() + " b ON a." + colA.getName() + " = b." + colB.getName()
                    + " WHERE (" + whereSql + ")";
        } else {
            // Single-table complex scan & order query
            String projCols = tableA.getColumns().stream().map(GaussDBColumn::getName).collect(Collectors.joining(", "));
            query = "SELECT " + projCols + " FROM " + tableA.getName() + " WHERE (" + whereSql + ") ORDER BY "
                    + colA.getName();
        }

        PuppyConstraintScenario scenario = Randomly.fromOptions(PuppyConstraintScenario.values());
        PuppyBundle bundle = new PuppyBundle(scenario, query, scenario.getGucCommands());

        SQLConnection connection = state.getConnection();
        try (Statement statement = connection.createStatement()) {
            // 1. Run baseline under default GUCs
            resetPuppyGucs(statement);
            List<String> defaultRows = executeAndGetRows(statement, query);

            // 2. Run under constrained GUCs
            for (String guc : bundle.getGucCommands()) {
                statement.execute(guc);
            }
            List<String> constrainedRows = executeAndGetRows(statement, query);

            // 3. Reset GUCs immediately
            resetPuppyGucs(statement);

            try {
                verifyPuppyInvariants(bundle, defaultRows, constrainedRows);
            } catch (AssertionError e) {
                // Retry once to eliminate transient timing differences
                resetPuppyGucs(statement);
                defaultRows = executeAndGetRows(statement, query);
                for (String guc : bundle.getGucCommands()) {
                    statement.execute(guc);
                }
                constrainedRows = executeAndGetRows(statement, query);
                resetPuppyGucs(statement);
                try {
                    verifyPuppyInvariants(bundle, defaultRows, constrainedRows);
                } catch (AssertionError ex) {
                    reproducer = new GaussDBPuppyReproducer(bundle);
                    throw ex;
                }
            }
        } catch (SQLException e) {
            try (Statement resetStmt = connection.createStatement()) {
                resetPuppyGucs(resetStmt);
            } catch (Exception ignored) {
            }
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        }
    }

    public static void verifyPuppyInvariants(PuppyBundle bundle, List<String> defaultRows, List<String> constrainedRows) {
        Set<String> s1 = new HashSet<>(defaultRows);
        Set<String> s2 = new HashSet<>(constrainedRows);
        if (!s1.equals(s2)) {
            throw new AssertionError(String.format(
                    "PUPPY Constrained Optimizer Equivalence Violation (%s)!\n"
                            + "GUC: %s\nQuery: %s\nDefault Rows (%d): %s\nConstrained Rows (%d): %s",
                    bundle.getScenario(), bundle.getGucCommands(), bundle.getQuery(),
                    defaultRows.size(), defaultRows, constrainedRows.size(), constrainedRows));
        }
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

    static void resetPuppyGucs(Statement statement) throws SQLException {
        for (String resetCommand : PUPPY_RESET_GUC_COMMANDS) {
            statement.execute(resetCommand);
        }
    }

    @Override
    public Reproducer<GaussDBGlobalState> getLastReproducer() {
        return reproducer;
    }

}
