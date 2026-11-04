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
import sqlancer.gaussdb.ast.GaussDBBinaryOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

/**
 * Abstract Rule Guided Fuzzing (ARG, ASE'25) oracle for GaussDB.
 *
 * <p>
 * Evaluates semantic equivalence across GaussDB's 28 dedicated query rewrite rules ({@code rewrite_rule}),
 * comparing queries executed under {@code SET rewrite_rule = 'none'} (unoptimized baseline) versus
 * specific combinations of rewrite rules (e.g. {@code cse_rewrite_opt}, {@code uniquecheck},
 * {@code partialpush}, {@code magicset}, {@code groupby_pushdown_subquery},
 * {@code enable_corr_any_sublink_semijoin_to_innerjoin}, {@code enable_sublink_pullup_enhanced}).
 *
 * Any discrepancy isolates a high-value bug in GaussDB's Query Rewriter subsystem.
 * </p>
 */
public class GaussDBARGOracle implements TestOracle<GaussDBGlobalState> {

    public static final List<String> ALL_REWRITE_RULES = Collections.unmodifiableList(Arrays.asList(
            "lazyagg",
            "magicset",
            "partialpush",
            "uniquecheck",
            "intargetlist",
            "predpush",
            "predpushnormal",
            "predpushforce",
            "disable_pullup_expr_sublink",
            "enable_sublink_pullup_enhanced",
            "disable_pullup_not_in_sublink",
            "disable_rownum_pushdown",
            "disable_windowagg_pushdown",
            "groupby_pushdown_subquery",
            "cse_rewrite_opt",
            "pullup_in_related",
            "disable_reduce_unused_targetlist",
            "disable_in_sublink_reduce_distinct",
            "disable_sublink_reduce_limit",
            "disable_fk_pk_outer_join_to_inner_join",
            "disable_self_join_elimination",
            "disable_sublink_outer_join_elimination",
            "enable_corr_any_sublink_semijoin_to_innerjoin",
            "enable_sublink_pullup_rownum",
            "disable_reduce_orderby",
            "enable_not_in_sublink_reduce_distinct"
    ));

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBARGReproducer reproducer;

    public GaussDBARGOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());

        String query = generateTargetQuery(table);
        if (query == null) {
            return;
        }

        List<String> selectedRules = pickRules();
        String ruleString = String.join(", ", selectedRules);

        SQLConnection connection = state.getConnection();
        try (Statement statement = connection.createStatement()) {
            try {
                // 1. Run baseline with rewrite rules disabled
                statement.execute("SET rewrite_rule = 'none'");
                List<String> baselineResults = executeAndGetRows(statement, query);

                // 2. Run variant with rewrite rules enabled
                statement.execute("SET rewrite_rule = '" + ruleString + "'");
                List<String> variantResults = executeAndGetRows(statement, query);

                // 3. Compare
                if (!areEquivalent(baselineResults, variantResults)) {
                    // Retry once to eliminate non-deterministic expressions
                    statement.execute("SET rewrite_rule = 'none'");
                    baselineResults = executeAndGetRows(statement, query);

                    statement.execute("SET rewrite_rule = '" + ruleString + "'");
                    variantResults = executeAndGetRows(statement, query);

                    if (!areEquivalent(baselineResults, variantResults)) {
                        // Delta debugging: identify specific offending rule
                        String offendingRule = null;
                        for (String singleRule : selectedRules) {
                            try {
                                statement.execute("SET rewrite_rule = '" + singleRule + "'");
                                List<String> singleResults = executeAndGetRows(statement, query);
                                if (!areEquivalent(baselineResults, singleResults)) {
                                    offendingRule = singleRule;
                                    break;
                                }
                            } catch (SQLException ignored) {
                            }
                        }

                        reproducer = new GaussDBARGReproducer(query, offendingRule, selectedRules);
                        throw new AssertionError("ARG Query Rewriter Mismatch"
                                + (offendingRule != null ? " under single rule [" + offendingRule + "]" : " under rules [" + ruleString + "]")
                                + "\nQuery:           " + query
                                + "\nActiveRules:     " + ruleString
                                + "\nBaselineResults: " + baselineResults
                                + "\nVariantResults:  " + variantResults);
                    }
                }
            } finally {
                try {
                    statement.execute("RESET rewrite_rule");
                } catch (SQLException ignored) {
                }
            }
        }
    }

    private List<String> pickRules() {
        int count = (int) Randomly.getNotCachedInteger(1, 4);
        List<String> shuffled = new ArrayList<>(ALL_REWRITE_RULES);
        Collections.shuffle(shuffled);
        return shuffled.subList(0, count);
    }

    private String generateTargetQuery(GaussDBTable table) {
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(table.getColumns());
        generator.setTableAlias("t");
        GaussDBExpression wherePred = generator.generateExpression(GaussDBDataType.BOOLEAN);
        String wherePredSql = GaussDBToStringVisitor.asString(wherePred);

        String selectCols = table.getColumns().stream()
                .map(c -> "t." + c.getName())
                .collect(Collectors.joining(", "));

        int queryType = Randomly.fromOptions(0, 1, 2, 3);
        switch (queryType) {
        case 0: {
            // Subquery in WHERE (IN / EXISTS)
            GaussDBColumn numCol = table.getColumns().get(0);
            return "SELECT DISTINCT " + selectCols + " FROM " + table.getName() + " AS t WHERE ("
                    + wherePredSql + ") AND t." + numCol.getName() + " IN (SELECT " + numCol.getName()
                    + " FROM " + table.getName() + " WHERE " + numCol.getName() + " IS NOT NULL)";
        }
        case 1: {
            // Group By and Having
            GaussDBColumn groupCol = Randomly.fromList(table.getColumns());
            return "SELECT t." + groupCol.getName() + ", count(*), min(t." + groupCol.getName() + ") FROM "
                    + table.getName() + " AS t WHERE (" + wherePredSql + ") GROUP BY t." + groupCol.getName()
                    + " HAVING count(*) >= 0";
        }
        case 2: {
            // Self-Join
            GaussDBColumn joinCol = Randomly.fromList(table.getColumns());
            return "SELECT DISTINCT " + selectCols + " FROM " + table.getName() + " AS t LEFT JOIN "
                    + table.getName() + " AS r ON (t." + joinCol.getName() + " = r." + joinCol.getName()
                    + ") WHERE (" + wherePredSql + ")";
        }
        default: {
            // Standard complex projection with DISTINCT
            return "SELECT DISTINCT " + selectCols + " FROM " + table.getName() + " AS t WHERE ("
                    + wherePredSql + ")";
        }
        }
    }

    private static boolean areEquivalent(List<String> list1, List<String> list2) {
        if (list1.size() != list2.size()) {
            return false;
        }
        List<String> sorted1 = new ArrayList<>(list1);
        List<String> sorted2 = new ArrayList<>(list2);
        sorted1.sort(Comparator.naturalOrder());
        sorted2.sort(Comparator.naturalOrder());
        return sorted1.equals(sorted2);
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
