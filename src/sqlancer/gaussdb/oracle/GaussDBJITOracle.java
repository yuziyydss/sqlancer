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
import sqlancer.gaussdb.ast.GaussDBWindowFunction;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

/**
 * Differential JIT Testing Oracle for GaussDB.
 *
 * <p>
 * Forces a query to execute under multiple JIT compilation configurations (interpreted execution with
 * {@code jit = off} vs forced full LLVM compilation with {@code jit = on, jit_above_cost = 0},
 * expression-only JIT, and tuple-deforming-only JIT) and verifies that all execution paths produce identical results.
 * </p>
 */
public class GaussDBJITOracle implements TestOracle<GaussDBGlobalState> {

    private static final String TABLE_ALIAS = "t";

    public static final List<String> RESET_JIT_GUCS = Collections.unmodifiableList(Arrays.asList(
            "RESET jit",
            "RESET jit_above_cost",
            "RESET jit_inline_above_cost",
            "RESET jit_optimize_above_cost",
            "RESET jit_expressions",
            "RESET jit_tuple_deforming"
    ));

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBJITReproducer reproducer;
    private String lastQueryString;

    public GaussDBJITOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public static final class JITVariant {

        private final String name;
        private final List<String> gucSettings;

        public JITVariant(String name, List<String> gucSettings) {
            this.name = name;
            this.gucSettings = Collections.unmodifiableList(new ArrayList<>(gucSettings));
        }

        public String getName() {
            return name;
        }

        public List<String> getGucSettings() {
            return gucSettings;
        }
    }

    public static List<JITVariant> getJITVariants() {
        List<JITVariant> variants = new ArrayList<>();
        variants.add(new JITVariant("FULL_JIT", Arrays.asList(
                "SET jit = on",
                "SET jit_above_cost = 0",
                "SET jit_inline_above_cost = 0",
                "SET jit_optimize_above_cost = 0",
                "SET jit_expressions = on",
                "SET jit_tuple_deforming = on"
        )));
        variants.add(new JITVariant("EXPRESSIONS_ONLY_JIT", Arrays.asList(
                "SET jit = on",
                "SET jit_above_cost = 0",
                "SET jit_expressions = on",
                "SET jit_tuple_deforming = off"
        )));
        variants.add(new JITVariant("DEFORMING_ONLY_JIT", Arrays.asList(
                "SET jit = on",
                "SET jit_above_cost = 0",
                "SET jit_expressions = off",
                "SET jit_tuple_deforming = on"
        )));
        return Collections.unmodifiableList(variants);
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());

        String sql;
        switch (Randomly.fromOptions(0, 1, 2, 3, 4)) {
        case 0:
            sql = buildComplexExpressionQuery(table);
            break;
        case 1:
            sql = buildMultiColumnDeformingQuery(table);
            break;
        case 2:
            sql = buildAggregateQuery(table);
            break;
        case 3:
            sql = buildWindowQuery(table);
            break;
        case 4:
            sql = buildWhereFilterQuery(table);
            break;
        default:
            throw new AssertionError();
        }
        if (sql == null || sql.isEmpty()) {
            return;
        }
        verifyJITEquivalence(sql);
    }

    private String buildComplexExpressionQuery(GaussDBTable table) {
        GaussDBExpressionGenerator generator = createGenerator(table, TABLE_ALIAS);
        GaussDBExpression expr = generator.generateExpression(GaussDBDataType.getRandomType());
        GaussDBSelect select = GaussDBEETOracle.selectValueFrom(table, expr);
        select.setWhereClause(generator.generateExpression(GaussDBDataType.BOOLEAN));
        return GaussDBToStringVisitor.asString(select);
    }

    private String buildMultiColumnDeformingQuery(GaussDBTable table) {
        GaussDBSelect select = new GaussDBSelect();
        List<GaussDBExpression> fetchColumns = table.getColumns().stream()
                .map(col -> new GaussDBColumnReference(col, TABLE_ALIAS))
                .collect(Collectors.toList());
        select.setFetchColumns(fetchColumns);
        select.setFromList(Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS)));
        select.setWhereClause(createGenerator(table, TABLE_ALIAS).generateExpression(GaussDBDataType.BOOLEAN));
        return GaussDBToStringVisitor.asString(select);
    }

    private String buildAggregateQuery(GaussDBTable table) {
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
        return GaussDBToStringVisitor.asString(select);
    }

    private String buildWindowQuery(GaussDBTable table) {
        GaussDBColumn partCol = Randomly.fromList(table.getColumns());
        GaussDBColumn orderCol = Randomly.fromList(table.getColumns());
        String fnName = Randomly.fromOptions("row_number", "rank", "dense_rank");
        GaussDBWindowFunction win = new GaussDBWindowFunction(fnName, Collections.emptyList(),
                Collections.singletonList(new GaussDBColumnReference(partCol, TABLE_ALIAS)),
                Collections.singletonList(new GaussDBColumnReference(orderCol, TABLE_ALIAS)), GaussDBDataType.INT);
        GaussDBSelect select = GaussDBEETOracle.selectValueFrom(table, win);
        select.setWhereClause(createGenerator(table, TABLE_ALIAS).generateExpression(GaussDBDataType.BOOLEAN));
        return GaussDBToStringVisitor.asString(select);
    }

    private String buildWhereFilterQuery(GaussDBTable table) {
        GaussDBExpression predicate = createGenerator(table, TABLE_ALIAS).generateExpression(GaussDBDataType.BOOLEAN);
        return GaussDBToStringVisitor.asString(GaussDBEETOracle.selectStarWhere(table, predicate));
    }

    private void verifyJITEquivalence(String query) throws SQLException {
        this.lastQueryString = query;
        SQLConnection con = state.getConnection();
        List<String> baselineRows;

        try (Statement statement = con.createStatement()) {
            // Step 1: Execute Baseline in Interpreted Mode (jit = off)
            statement.execute("SET jit = off");
            try {
                baselineRows = executeQuery(statement, query);
            } catch (SQLException e) {
                if (errors.errorIsExpected(e.getMessage())) {
                    return;
                }
                throw e;
            } finally {
                quietlyResetJIT(statement);
            }

            // Step 2: Compare against JIT compilation variants
            for (JITVariant variant : getJITVariants()) {
                for (String guc : variant.getGucSettings()) {
                    statement.execute(guc);
                }
                try {
                    List<String> jitRows = executeQuery(statement, query);
                    if (!areEquivalent(baselineRows, jitRows)) {
                        this.reproducer = new GaussDBJITReproducer(query, variant);
                        throw new AssertionError(String.format(
                                "JIT Differential Violation!%nQuery: %s%nInterpreted (SET jit = off) Rows (%d): %s%nJIT Variant [%s] Rows (%d): %s",
                                query, baselineRows.size(), baselineRows, variant.getName(), jitRows.size(), jitRows));
                    }
                } catch (SQLException e) {
                    if (errors.errorIsExpected(e.getMessage())) {
                        continue;
                    }
                    this.reproducer = new GaussDBJITReproducer(query, variant);
                    throw new AssertionError(String.format(
                            "JIT Variant [%s] failed with unexpected error while Interpreted succeeded!%nQuery: %s%nError: %s",
                            variant.getName(), query, e.getMessage()), e);
                } finally {
                    quietlyResetJIT(statement);
                }
            }
        }
    }

    static boolean areEquivalent(List<String> list1, List<String> list2) {
        if (list1.size() != list2.size()) {
            return false;
        }
        List<String> sorted1 = new ArrayList<>(list1);
        List<String> sorted2 = new ArrayList<>(list2);
        sorted1.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
        sorted2.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
        return sorted1.equals(sorted2);
    }

    private List<String> executeQuery(Statement statement, String query) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(query)) {
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

    private void quietlyResetJIT(Statement statement) {
        for (String reset : RESET_JIT_GUCS) {
            try {
                statement.execute(reset);
            } catch (SQLException ignored) {
                // Ignore transient reset error
            }
        }
    }

    private GaussDBExpressionGenerator createGenerator(GaussDBTable table, String alias) {
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(table.getColumns());
        generator.setTableAlias(alias);
        return generator;
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
