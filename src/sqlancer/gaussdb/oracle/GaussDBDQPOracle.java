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
import sqlancer.gaussdb.ast.GaussDBBinaryOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBJoin;
import sqlancer.gaussdb.ast.GaussDBSelect;
import sqlancer.gaussdb.ast.GaussDBSetOperation;
import sqlancer.gaussdb.ast.GaussDBStar;
import sqlancer.gaussdb.ast.GaussDBWindowFunction;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

/**
 * Differential Query Plans (DQP, ICSE'24) oracle for GaussDB.
 *
 * <p>
 * Forces a query to execute under multiple distinct optimizer/executor plan configurations (combining GaussDB GUC
 * operator switches, Sonic HashAgg/HashJoin, OpFusion, {@code query_dop} SMP parallel execution, {@code rewrite_rule},
 * and inline {@code /*+ ... *&#47;} plan hints) and verifies that all query plans produce identical results.
 * </p>
 */
public class GaussDBDQPOracle implements TestOracle<GaussDBGlobalState> {

    private static final String TABLE_ALIAS = "t";

    static final List<String> RESET_GUCS = Collections.unmodifiableList(Arrays.asList("RESET enable_hashjoin",
            "RESET enable_nestloop", "RESET enable_mergejoin", "RESET enable_indexscan", "RESET enable_indexonlyscan",
            "RESET enable_bitmapscan", "RESET enable_seqscan", "RESET enable_sort", "RESET enable_hashagg",
            "RESET enable_sonic_hashagg", "RESET enable_sonic_hashjoin", "RESET enable_opfusion", "SET query_dop = 1",
            "SET rewrite_rule = 'magicset,groupby_pushdown_subquery,pullup_in_related'"));

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBDQPReproducer reproducer;
    private String lastQueryString;

    public GaussDBDQPOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public static final class PlanVariant {

        private final String name;
        private final List<String> gucSettings;
        private final String sql;

        PlanVariant(String name, List<String> gucSettings, String sql) {
            this.name = name;
            this.gucSettings = Collections.unmodifiableList(new ArrayList<>(gucSettings));
            this.sql = sql;
        }

        public String getName() {
            return name;
        }

        public List<String> getGucSettings() {
            return gucSettings;
        }

        public String getSql() {
            return sql;
        }
    }

    public static final class DQPPlanBundle {

        private final String baseQuery;
        private final List<PlanVariant> variants;

        DQPPlanBundle(String baseQuery, List<PlanVariant> variants) {
            this.baseQuery = baseQuery;
            this.variants = Collections.unmodifiableList(new ArrayList<>(variants));
        }

        public String getBaseQuery() {
            return baseQuery;
        }

        public List<PlanVariant> getVariants() {
            return variants;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());

        DQPPlanBundle bundle;
        switch (Randomly.fromOptions(0, 1, 2, 3, 4, 5, 6)) {
        case 0:
            bundle = buildWhereBundle(table);
            break;
        case 1:
            bundle = buildGroupByHavingBundle(table);
            break;
        case 2:
            bundle = buildAggregateBundle(table);
            break;
        case 3:
            bundle = buildDistinctBundle(table);
            break;
        case 4:
            bundle = buildWindowBundle(table);
            break;
        case 5:
            bundle = buildSetOperationBundle(table);
            break;
        case 6:
            bundle = buildJoinBundle();
            break;
        default:
            throw new AssertionError();
        }
        if (bundle == null) {
            return;
        }
        verifyBundle(bundle);
    }

    private DQPPlanBundle buildWhereBundle(GaussDBTable table) {
        GaussDBExpression predicate = createGenerator(table, TABLE_ALIAS).generateExpression(GaussDBDataType.BOOLEAN);
        String sql = GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarWhere(table, predicate));
        return createBundle(sql, false);
    }

    private DQPPlanBundle buildGroupByHavingBundle(GaussDBTable table) {
        GaussDBColumn col = Randomly.fromList(table.getColumns());
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(Collections.singletonList(col));
        generator.setTableAlias(TABLE_ALIAS);
        GaussDBExpression havingPred = generator.generateExpression(GaussDBDataType.BOOLEAN);
        String sql = GaussDBToStringVisitor
                .asString(GaussDBEETOracle.selectGroupByHaving(table, col, havingPred));
        return createBundle(sql, false);
    }

    private DQPPlanBundle buildAggregateBundle(GaussDBTable table) {
        List<GaussDBColumn> numericCols = table.getColumns().stream()
                .filter(c -> c.getType() == GaussDBDataType.INT || c.getType() == GaussDBDataType.DECIMAL)
                .collect(Collectors.toList());
        GaussDBExpression agg;
        if (numericCols.isEmpty()) {
            agg = new GaussDBAggregate(GaussDBAggregate.Function.COUNT, Collections.singletonList(new GaussDBStar()),
                    GaussDBDataType.INT);
        } else {
            GaussDBColumn col = Randomly.fromList(numericCols);
            GaussDBAggregate.Function fn = Randomly.fromOptions(GaussDBAggregate.Function.COUNT,
                    GaussDBAggregate.Function.SUM, GaussDBAggregate.Function.MIN, GaussDBAggregate.Function.MAX);
            agg = new GaussDBAggregate(fn, Collections.singletonList(new GaussDBColumnReference(col, TABLE_ALIAS)),
                    col.getType());
        }
        GaussDBSelect select = GaussDBEETOracle.selectAggregateFrom(table, agg);
        select.setWhereClause(createGenerator(table, TABLE_ALIAS).generateExpression(GaussDBDataType.BOOLEAN));
        return createBundle(GaussDBToStringVisitor.asString(select), false);
    }

    private DQPPlanBundle buildDistinctBundle(GaussDBTable table) {
        GaussDBExpression expr = createGenerator(table, TABLE_ALIAS)
                .generateExpression(GaussDBDataType.getRandomType());
        GaussDBSelect select = GaussDBEETOracle.selectValueFrom(table, expr);
        select.setDistinct(true);
        return createBundle(GaussDBToStringVisitor.asString(select), false);
    }

    private DQPPlanBundle buildWindowBundle(GaussDBTable table) {
        GaussDBColumn partCol = Randomly.fromList(table.getColumns());
        GaussDBColumn orderCol = Randomly.fromList(table.getColumns());
        String fnName = Randomly.fromOptions("row_number", "rank", "dense_rank");
        GaussDBWindowFunction win = new GaussDBWindowFunction(fnName, Collections.emptyList(),
                Collections.singletonList(new GaussDBColumnReference(partCol, TABLE_ALIAS)),
                Collections.singletonList(new GaussDBColumnReference(orderCol, TABLE_ALIAS)), GaussDBDataType.INT);
        String sql = GaussDBToStringVisitor.asString(GaussDBEETOracle.selectValueFrom(table, win));
        return createBundle(sql, false);
    }

    private DQPPlanBundle buildSetOperationBundle(GaussDBTable table) {
        GaussDBExpressionGenerator generator = createGenerator(table, TABLE_ALIAS);
        GaussDBSetOperation setOp = new GaussDBSetOperation(
                GaussDBEETOracle.selectStarWhere(table, generator.generateExpression(GaussDBDataType.BOOLEAN)),
                GaussDBEETOracle.selectStarWhere(table, generator.generateExpression(GaussDBDataType.BOOLEAN)),
                GaussDBSetOperation.Operator.getRandom());
        return createBundle(GaussDBToStringVisitor.asString(setOp), false);
    }

    private DQPPlanBundle buildJoinBundle() {
        GaussDBTable leftTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBTable rightTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBColumn leftCol = Randomly.fromList(leftTable.getColumns());
        GaussDBColumn rightCol = null;
        for (GaussDBColumn candidate : rightTable.getColumns()) {
            if (candidate.getType() == leftCol.getType()) {
                rightCol = candidate;
                break;
            }
        }
        if (rightCol == null) {
            return null;
        }
        GaussDBJoin.JoinType joinType = Randomly.fromOptions(GaussDBJoin.JoinType.INNER, GaussDBJoin.JoinType.LEFT,
                GaussDBJoin.JoinType.RIGHT, GaussDBJoin.JoinType.FULL);
        GaussDBBinaryOperation.Operator op = joinType == GaussDBJoin.JoinType.FULL
                ? GaussDBBinaryOperation.Operator.EQUALS : GaussDBBinaryOperation.Operator.getRandomComparison();
        GaussDBExpression onPred = new GaussDBBinaryOperation(new GaussDBColumnReference(leftCol, "l"),
                new GaussDBColumnReference(rightCol, "r"), op, GaussDBDataType.BOOLEAN);
        GaussDBSelect joinSelect = GaussDBEETOracle.selectStarJoin(leftTable, rightTable, joinType, onPred);
        return createBundle(GaussDBToStringVisitor.asString(joinSelect), true);
    }

    private GaussDBExpressionGenerator createGenerator(GaussDBTable table, String alias) {
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(table.getColumns());
        generator.setTableAlias(alias);
        return generator;
    }

    static DQPPlanBundle createBundle(String baseQuery, boolean isJoinQuery) {
        List<PlanVariant> variants = new ArrayList<>();
        variants.add(new PlanVariant("nestloop_index_no_opfusion",
                Arrays.asList("SET enable_hashjoin = off", "SET enable_mergejoin = off", "SET enable_nestloop = on",
                        "SET enable_seqscan = off", "SET enable_indexscan = on", "SET enable_opfusion = off",
                        "SET query_dop = 1", "SET rewrite_rule = 'none'"),
                isJoinQuery ? injectHint(baseQuery, "NestLoop(l r) IndexScan(l)") : injectHint(baseQuery, "IndexScan(t)")));

        variants.add(new PlanVariant("hash_sonic_seqscan",
                Arrays.asList("SET enable_nestloop = off", "SET enable_mergejoin = off", "SET enable_hashjoin = on",
                        "SET enable_sonic_hashjoin = on", "SET enable_sonic_hashagg = on", "SET enable_indexscan = off",
                        "SET enable_bitmapscan = off", "SET enable_seqscan = on", "SET query_dop = 1"),
                isJoinQuery ? injectHint(baseQuery, "HashJoin(l r) TableScan(l)") : injectHint(baseQuery, "TableScan(t)")));

        variants.add(new PlanVariant("merge_smp_rewrite",
                Arrays.asList("SET enable_nestloop = off", "SET enable_hashjoin = off", "SET enable_mergejoin = on",
                        "SET enable_sort = on", "SET query_dop = 4",
                        "SET rewrite_rule = 'lazyagg,magicset,partialpush,uniquecheck,intargetlist,predpushforce,"
                                + "enable_sublink_pullup_enhanced,groupby_pushdown_subquery,cse_rewrite_opt,"
                                + "pullup_in_related'"),
                isJoinQuery ? injectHint(baseQuery, "MergeJoin(l r)") : baseQuery));

        return new DQPPlanBundle(baseQuery, variants);
    }

    static String injectHint(String sql, String hintBody) {
        if (sql.startsWith("SELECT ")) {
            return "SELECT /*+ " + hintBody + " */ " + sql.substring("SELECT ".length());
        }
        return sql;
    }

    private void verifyBundle(DQPPlanBundle bundle) throws SQLException {
        lastQueryString = bundle.getBaseQuery();
        SQLConnection connection = state.getConnection();
        try (Statement statement = connection.createStatement()) {
            try {
                applyGucs(statement, RESET_GUCS);
                List<String> baselineRows = executeAndGetRows(statement, bundle.getBaseQuery(), errors);

                for (PlanVariant variant : bundle.getVariants()) {
                    applyGucs(statement, RESET_GUCS);
                    applyGucs(statement, variant.getGucSettings());
                    List<String> variantRows = executeAndGetRows(statement, variant.getSql(), errors);
                    if (!areEquivalent(baselineRows, variantRows)) {
                        // Re-run once to rule out nondeterministic expressions.
                        applyGucs(statement, RESET_GUCS);
                        List<String> retryBase = executeAndGetRows(statement, bundle.getBaseQuery(), errors);
                        applyGucs(statement, variant.getGucSettings());
                        List<String> retryVariant = executeAndGetRows(statement, variant.getSql(), errors);
                        if (!areEquivalent(retryBase, retryVariant)) {
                            reproducer = new GaussDBDQPReproducer(bundle.getBaseQuery(), variant);
                            throw new AssertionError("DQP plan mismatch on profile " + variant.getName()
                                    + "\nBaselineQuery: " + bundle.getBaseQuery() + "\nVariantQuery: "
                                    + variant.getSql() + "\nVariantGUCs: " + variant.getGucSettings()
                                    + "\nBaselineResult: " + retryBase + "\nVariantResult: " + retryVariant);
                        }
                    }
                }
            } finally {
                quietlyResetGucs(statement);
            }
        }
    }

    static void applyGucs(Statement statement, List<String> gucs) throws SQLException {
        if (!gucs.isEmpty()) {
            statement.execute(String.join("; ", gucs));
        }
    }

    static List<String> executeAndGetRows(Statement statement, String query, ExpectedErrors errors)
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

    static void quietlyResetGucs(Statement statement) {
        try {
            statement.execute(String.join("; ", RESET_GUCS));
        } catch (SQLException ignored) {
            // Best-effort reset.
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
