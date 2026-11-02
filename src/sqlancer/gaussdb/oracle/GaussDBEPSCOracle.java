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
import sqlancer.gaussdb.ast.GaussDBAlias;
import sqlancer.gaussdb.ast.GaussDBBetweenOperation;
import sqlancer.gaussdb.ast.GaussDBBinaryOperation;
import sqlancer.gaussdb.ast.GaussDBCaseOperation;
import sqlancer.gaussdb.ast.GaussDBCastOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBExpressionBag;
import sqlancer.gaussdb.ast.GaussDBFunction;
import sqlancer.gaussdb.ast.GaussDBInOperation;
import sqlancer.gaussdb.ast.GaussDBJoin;
import sqlancer.gaussdb.ast.GaussDBSelect;
import sqlancer.gaussdb.ast.GaussDBSetOperation;
import sqlancer.gaussdb.ast.GaussDBStar;
import sqlancer.gaussdb.ast.GaussDBSubquery;
import sqlancer.gaussdb.ast.GaussDBTableReference;
import sqlancer.gaussdb.ast.GaussDBUnaryOperation;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

/**
 * Equivalent Prepared Statement Construction (EPSC, SIGMOD'26) oracle for GaussDB.
 *
 * <p>
 * Executes a generated SQL query directly and compares its result set against preparing the parameterized query
 * ({@code PREPARE ... AS ...}) and executing it under both {@code force_custom_plan} and {@code force_generic_plan}
 * (plus repeated executions in {@code auto} mode to exercise the custom-to-generic plan cache transition).
 * </p>
 */
public class GaussDBEPSCOracle implements TestOracle<GaussDBGlobalState> {

    static final String STATEMENT_NAME = "sqlancer_epsc_stmt";
    private static final String TABLE_ALIAS = "t";

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBEPSCReproducer reproducer;
    private String lastQueryString;

    public GaussDBEPSCOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
        errors.add(" indeterminate data type");
        errors.add("could not determine data type of parameter");
    }

    public static final class PreparedQueryPlan {

        private final String originalQuery;
        private final String prepareSql;
        private final String executeSql;
        private final String deallocateSql;
        private final List<GaussDBConstant> parameters;

        PreparedQueryPlan(String originalQuery, String prepareSql, String executeSql, String deallocateSql,
                List<GaussDBConstant> parameters) {
            this.originalQuery = originalQuery;
            this.prepareSql = prepareSql;
            this.executeSql = executeSql;
            this.deallocateSql = deallocateSql;
            this.parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
        }

        public String getOriginalQuery() {
            return originalQuery;
        }

        public String getPrepareSql() {
            return prepareSql;
        }

        public String getExecuteSql() {
            return executeSql;
        }

        public String getDeallocateSql() {
            return deallocateSql;
        }

        public List<GaussDBConstant> getParameters() {
            return parameters;
        }
    }

    private static final class ParameterPlaceholder extends GaussDBConstant {

        private final int index;
        private final GaussDBDataType type;

        ParameterPlaceholder(int index, GaussDBDataType type) {
            this.index = index;
            this.type = type;
        }

        @Override
        public String getTextRepresentation() {
            return "$" + index;
        }

        @Override
        public GaussDBDataType getExpressionType() {
            return type;
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

        PreparedQueryPlan plan;
        switch (Randomly.fromOptions(0, 1, 2, 3, 4, 5, 6, 7)) {
        case 0:
            plan = buildWherePlan(table, generator);
            break;
        case 1:
            plan = buildSelectListPlan(table, generator);
            break;
        case 2:
            plan = buildHavingPlan(table);
            break;
        case 3:
            plan = buildJoinPlan();
            break;
        case 4:
            plan = buildDerivedTablePlan(table, generator);
            break;
        case 5:
            plan = buildCtePlan(table, generator);
            break;
        case 6:
            plan = buildSetOperationPlan(table, generator);
            break;
        case 7:
            plan = buildAggregatePlan(table, generator);
            break;
        default:
            throw new AssertionError();
        }
        if (plan == null) {
            return;
        }
        verifyPlan(plan);
    }

    private PreparedQueryPlan buildWherePlan(GaussDBTable table, GaussDBExpressionGenerator generator) {
        GaussDBExpression predicate = ensureTypedConstant(generator.generateExpression(GaussDBDataType.BOOLEAN),
                state.getRandomly());
        GaussDBSelect original = GaussDBEETOracle.selectStarWhere(table, predicate);
        List<GaussDBConstant> params = new ArrayList<>();
        GaussDBExpression parameterized = parameterizeConstants(predicate, params);
        GaussDBSelect prepared = GaussDBEETOracle.selectStarWhere(table, parameterized);
        return createPlan(original, prepared, params);
    }

    private PreparedQueryPlan buildSelectListPlan(GaussDBTable table, GaussDBExpressionGenerator generator) {
        GaussDBDataType type = GaussDBDataType.getRandomType();
        GaussDBExpression expr = generator.generateExpression(type);
        GaussDBExpression predicate = ensureTypedConstant(generator.generateExpression(GaussDBDataType.BOOLEAN),
                state.getRandomly());
        GaussDBSelect original = GaussDBEETOracle.selectValueFrom(table, expr);
        original.setWhereClause(predicate);

        List<GaussDBConstant> params = new ArrayList<>();
        GaussDBExpression paramExpr = parameterizeConstants(expr, params);
        GaussDBExpression paramPred = parameterizeConstants(predicate, params);
        GaussDBSelect prepared = GaussDBEETOracle.selectValueFrom(table, paramExpr);
        prepared.setWhereClause(paramPred);
        return createPlan(original, prepared, params);
    }

    private PreparedQueryPlan buildHavingPlan(GaussDBTable table) {
        GaussDBColumn column = Randomly.fromList(table.getColumns());
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(Collections.singletonList(column));
        generator.setTableAlias(TABLE_ALIAS);
        GaussDBExpression predicate = ensureTypedConstant(generator.generateExpression(GaussDBDataType.BOOLEAN),
                state.getRandomly());

        GaussDBSelect original = GaussDBEETOracle.selectGroupByHaving(table, column, predicate);
        List<GaussDBConstant> params = new ArrayList<>();
        GaussDBSelect prepared = GaussDBEETOracle.selectGroupByHaving(table, column,
                parameterizeConstants(predicate, params));
        return createPlan(original, prepared, params);
    }

    private PreparedQueryPlan buildJoinPlan() {
        GaussDBTable leftTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBTable rightTable = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());
        GaussDBColumn leftCol = Randomly.fromList(leftTable.getColumns());
        GaussDBColumn rightCol = Randomly.fromList(rightTable.getColumns());
        GaussDBJoin.JoinType joinType = Randomly.fromOptions(GaussDBJoin.JoinType.INNER, GaussDBJoin.JoinType.LEFT,
                GaussDBJoin.JoinType.RIGHT);
        GaussDBExpression onPredicate = new GaussDBBinaryOperation(new GaussDBColumnReference(leftCol, "l"),
                GaussDBExpressionGenerator.generateConstant(state.getRandomly(), leftCol.getType()),
                GaussDBBinaryOperation.Operator.getRandomComparison(), GaussDBDataType.BOOLEAN);
        GaussDBExpression wherePredicate = new GaussDBBinaryOperation(new GaussDBColumnReference(rightCol, "r"),
                GaussDBExpressionGenerator.generateConstant(state.getRandomly(), rightCol.getType()),
                GaussDBBinaryOperation.Operator.getRandomComparison(), GaussDBDataType.BOOLEAN);

        GaussDBSelect original = GaussDBEETOracle.selectStarJoin(leftTable, rightTable, joinType, onPredicate);
        original.setWhereClause(wherePredicate);

        List<GaussDBConstant> params = new ArrayList<>();
        GaussDBSelect prepared = GaussDBEETOracle.selectStarJoin(leftTable, rightTable, joinType,
                parameterizeConstants(onPredicate, params));
        prepared.setWhereClause(parameterizeConstants(wherePredicate, params));
        return createPlan(original, prepared, params);
    }

    private PreparedQueryPlan buildDerivedTablePlan(GaussDBTable table, GaussDBExpressionGenerator generator) {
        GaussDBExpression predicate = ensureTypedConstant(generator.generateExpression(GaussDBDataType.BOOLEAN),
                state.getRandomly());
        GaussDBSelect original = GaussDBEETOracle
                .selectStarFromDerivedTable(GaussDBEETOracle.selectStarWhere(table, predicate));
        List<GaussDBConstant> params = new ArrayList<>();
        GaussDBSelect prepared = GaussDBEETOracle.selectStarFromDerivedTable(
                GaussDBEETOracle.selectStarWhere(table, parameterizeConstants(predicate, params)));
        return createPlan(original, prepared, params);
    }

    private PreparedQueryPlan buildCtePlan(GaussDBTable table, GaussDBExpressionGenerator generator) {
        GaussDBExpression predicate = ensureTypedConstant(generator.generateExpression(GaussDBDataType.BOOLEAN),
                state.getRandomly());
        GaussDBSelect original = GaussDBEETOracle
                .selectStarFromCte(GaussDBEETOracle.selectStarWhere(table, predicate));
        List<GaussDBConstant> params = new ArrayList<>();
        GaussDBSelect prepared = GaussDBEETOracle
                .selectStarFromCte(GaussDBEETOracle.selectStarWhere(table, parameterizeConstants(predicate, params)));
        return createPlan(original, prepared, params);
    }

    private PreparedQueryPlan buildSetOperationPlan(GaussDBTable table, GaussDBExpressionGenerator generator) {
        GaussDBExpression leftPredicate = ensureTypedConstant(generator.generateExpression(GaussDBDataType.BOOLEAN),
                state.getRandomly());
        GaussDBExpression rightPredicate = ensureTypedConstant(generator.generateExpression(GaussDBDataType.BOOLEAN),
                state.getRandomly());
        GaussDBSetOperation.Operator op = GaussDBSetOperation.Operator.getRandom();

        GaussDBSetOperation original = new GaussDBSetOperation(GaussDBEETOracle.selectStarWhere(table, leftPredicate),
                GaussDBEETOracle.selectStarWhere(table, rightPredicate), op);
        List<GaussDBConstant> params = new ArrayList<>();
        GaussDBSetOperation prepared = new GaussDBSetOperation(
                GaussDBEETOracle.selectStarWhere(table, parameterizeConstants(leftPredicate, params)),
                GaussDBEETOracle.selectStarWhere(table, parameterizeConstants(rightPredicate, params)), op);
        return createPlan(original, prepared, params);
    }

    private PreparedQueryPlan buildAggregatePlan(GaussDBTable table, GaussDBExpressionGenerator generator) {
        GaussDBExpression predicate = ensureTypedConstant(generator.generateExpression(GaussDBDataType.BOOLEAN),
                state.getRandomly());
        GaussDBAggregate count = new GaussDBAggregate(GaussDBAggregate.Function.COUNT,
                Collections.singletonList(new GaussDBStar()), GaussDBDataType.INT);

        GaussDBSelect original = new GaussDBSelect();
        original.setFetchColumns(Collections.singletonList(new GaussDBAlias(count, "row_count")));
        original.setFromList(
                Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS)));
        original.setWhereClause(predicate);

        List<GaussDBConstant> params = new ArrayList<>();
        GaussDBSelect prepared = new GaussDBSelect();
        prepared.setFetchColumns(Collections.singletonList(new GaussDBAlias(count, "row_count")));
        prepared.setFromList(
                Collections.singletonList(new GaussDBAlias(new GaussDBTableReference(table), TABLE_ALIAS)));
        prepared.setWhereClause(parameterizeConstants(predicate, params));
        return createPlan(original, prepared, params);
    }

    static GaussDBExpression ensureTypedConstant(GaussDBExpression predicate, Randomly randomly) {
        List<GaussDBConstant> existing = new ArrayList<>();
        parameterizeConstants(predicate, existing);
        if (!existing.isEmpty()) {
            return predicate;
        }
        GaussDBConstant left = GaussDBExpressionGenerator.generateConstant(randomly, GaussDBDataType.INT) instanceof GaussDBConstant
                ? (GaussDBConstant) GaussDBExpressionGenerator.generateConstant(randomly, GaussDBDataType.INT)
                : GaussDBConstant.createIntConstant(1);
        GaussDBExpression trivialTrue = new GaussDBBinaryOperation(left, left, GaussDBBinaryOperation.Operator.EQUALS,
                GaussDBDataType.BOOLEAN);
        return new GaussDBBinaryOperation(predicate, trivialTrue, GaussDBBinaryOperation.Operator.AND,
                GaussDBDataType.BOOLEAN);
    }

    static GaussDBExpression parameterizeConstants(GaussDBExpression expression, List<GaussDBConstant> parameters) {
        if (expression instanceof GaussDBConstant && !(expression instanceof ParameterPlaceholder)) {
            GaussDBConstant constant = (GaussDBConstant) expression;
            if (!constant.isNull() && constant.getExpressionType() != null && parameters.size() < 8) {
                parameters.add(constant);
                return new ParameterPlaceholder(parameters.size(), constant.getExpressionType());
            }
            return expression;
        } else if (expression instanceof GaussDBBinaryOperation) {
            GaussDBBinaryOperation op = (GaussDBBinaryOperation) expression;
            return new GaussDBBinaryOperation(parameterizeConstants(op.getLeft(), parameters),
                    parameterizeConstants(op.getRight(), parameters), op.getOperator(), op.getExpressionType());
        } else if (expression instanceof GaussDBUnaryOperation) {
            GaussDBUnaryOperation op = (GaussDBUnaryOperation) expression;
            return new GaussDBUnaryOperation(parameterizeConstants(op.getExpression(), parameters), op.getOperator(),
                    op.getExpressionType());
        } else if (expression instanceof GaussDBCaseOperation) {
            GaussDBCaseOperation op = (GaussDBCaseOperation) expression;
            List<GaussDBExpression> conds = op.getConditions().stream()
                    .map(c -> parameterizeConstants(c, parameters)).collect(Collectors.toList());
            List<GaussDBExpression> results = op.getResults().stream()
                    .map(r -> parameterizeConstants(r, parameters)).collect(Collectors.toList());
            GaussDBExpression elseExpr = op.getElseExpression() == null ? null
                    : parameterizeConstants(op.getElseExpression(), parameters);
            return new GaussDBCaseOperation(conds, results, elseExpr, op.getExpressionType());
        } else if (expression instanceof GaussDBCastOperation) {
            GaussDBCastOperation op = (GaussDBCastOperation) expression;
            return new GaussDBCastOperation(parameterizeConstants(op.getExpression(), parameters),
                    op.getExpressionType());
        } else if (expression instanceof GaussDBFunction) {
            GaussDBFunction op = (GaussDBFunction) expression;
            // Keep date_part's first argument ('day', 'hour', ...) as a literal when preferred, though GaussDB also
            // accepts a TEXT parameter; parameterizing all arguments is valid SQL.
            List<GaussDBExpression> args = new ArrayList<>(op.getArguments().size());
            for (int i = 0; i < op.getArguments().size(); i++) {
                if (i == 0 && "date_part".equalsIgnoreCase(op.getFunctionName())) {
                    args.add(op.getArguments().get(i));
                } else {
                    args.add(parameterizeConstants(op.getArguments().get(i), parameters));
                }
            }
            return new GaussDBFunction(op.getFunctionName(), args, op.getExpressionType());
        } else if (expression instanceof GaussDBBetweenOperation) {
            GaussDBBetweenOperation op = (GaussDBBetweenOperation) expression;
            return new GaussDBBetweenOperation(parameterizeConstants(op.getExpression(), parameters),
                    parameterizeConstants(op.getLeft(), parameters), parameterizeConstants(op.getRight(), parameters),
                    op.isNegated(), op.isSymmetric());
        } else if (expression instanceof GaussDBInOperation) {
            GaussDBInOperation op = (GaussDBInOperation) expression;
            List<GaussDBExpression> list = op.getList().stream().map(e -> parameterizeConstants(e, parameters))
                    .collect(Collectors.toList());
            return new GaussDBInOperation(parameterizeConstants(op.getExpression(), parameters), list, op.isNegated());
        } else if (expression instanceof GaussDBExpressionBag) {
            GaussDBExpressionBag bag = (GaussDBExpressionBag) expression;
            return new GaussDBExpressionBag(parameterizeConstants(bag.getInnerExpression(), parameters));
        } else if (expression instanceof GaussDBAlias) {
            GaussDBAlias alias = (GaussDBAlias) expression;
            return new GaussDBAlias(parameterizeConstants(alias.getExpression(), parameters), alias.getAlias());
        } else if (expression instanceof GaussDBSubquery) {
            GaussDBSubquery sub = (GaussDBSubquery) expression;
            return new GaussDBSubquery(parameterizeConstants(sub.getExpression(), parameters));
        }
        return expression;
    }

    static PreparedQueryPlan createPlan(GaussDBExpression originalAst, GaussDBExpression parameterizedAst,
            List<GaussDBConstant> parameters) {
        if (parameters.isEmpty()) {
            return null;
        }
        String originalQuery = GaussDBToStringVisitor.asString(originalAst);
        String parameterizedBody = GaussDBToStringVisitor.asString(parameterizedAst);
        String paramTypes = parameters.stream().map(c -> sqlTypeName(c.getExpressionType()))
                .collect(Collectors.joining(", "));
        String paramValues = parameters.stream().map(GaussDBToStringVisitor::asString)
                .collect(Collectors.joining(", "));

        String prepareSql = "PREPARE " + STATEMENT_NAME + "(" + paramTypes + ") AS " + parameterizedBody;
        String executeSql = "EXECUTE " + STATEMENT_NAME + "(" + paramValues + ")";
        String deallocateSql = "DEALLOCATE PREPARE " + STATEMENT_NAME;
        return new PreparedQueryPlan(originalQuery, prepareSql, executeSql, deallocateSql, parameters);
    }

    static String sqlTypeName(GaussDBDataType type) {
        switch (type) {
        case INT:
            return "BIGINT";
        case BOOLEAN:
            return "BOOLEAN";
        case TEXT:
            return "TEXT";
        case DECIMAL:
            return "NUMERIC";
        case FLOAT:
            return "DOUBLE PRECISION";
        case REAL:
            return "REAL";
        case DATE:
            return "DATE";
        case TIMESTAMP:
            return "TIMESTAMP";
        case TIMESTAMPTZ:
            return "TIMESTAMPTZ";
        case TIME:
            return "TIME";
        case INTERVAL:
            return "INTERVAL";
        default:
            throw new AssertionError(type);
        }
    }

    private void verifyPlan(PreparedQueryPlan plan) throws SQLException {
        lastQueryString = plan.getOriginalQuery();
        SQLConnection connection = state.getConnection();
        try (Statement statement = connection.createStatement()) {
            quietlyDeallocate(statement, plan.getDeallocateSql());
            try {
                List<String> directResults = executeAndGetRows(statement, plan.getOriginalQuery());
                executeStatement(statement, plan.getPrepareSql());

                for (String mode : Arrays.asList("force_custom_plan", "force_generic_plan", "auto")) {
                    statement.execute("SET plan_cache_mode = " + mode);
                    int runs = "auto".equals(mode) ? 6 : 1;
                    for (int i = 0; i < runs; i++) {
                        List<String> preparedResults = executeAndGetRows(statement, plan.getExecuteSql());
                        if (!areEquivalent(directResults, preparedResults)) {
                            // Re-run once to rule out nondeterminism.
                            List<String> retryDirect = executeAndGetRows(statement, plan.getOriginalQuery());
                            List<String> retryPrepared = executeAndGetRows(statement, plan.getExecuteSql());
                            if (!areEquivalent(retryDirect, retryPrepared)) {
                                reproducer = new GaussDBEPSCReproducer(plan, mode);
                                throw new AssertionError("EPSC mismatch under plan_cache_mode=" + mode + "\nOriginal: "
                                        + plan.getOriginalQuery() + "\nPrepare: " + plan.getPrepareSql() + "\nExecute: "
                                        + plan.getExecuteSql() + "\nDirectResult: " + retryDirect + "\nPreparedResult: "
                                        + retryPrepared);
                            }
                        }
                    }
                }
            } finally {
                quietlyResetPlanCacheMode(statement);
                quietlyDeallocate(statement, plan.getDeallocateSql());
            }
        }
    }

    private void executeStatement(Statement statement, String sql) throws SQLException {
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
                    row.add(resultSet.getString(i));
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

    static boolean areEquivalent(List<String> first, List<String> second) {
        return sorted(first).equals(sorted(second));
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
        return copy;
    }

    private static void quietlyDeallocate(Statement statement, String deallocateSql) {
        try {
            statement.execute(deallocateSql);
        } catch (SQLException ignored) {
            // Ignore when the statement was not prepared yet.
        }
    }

    private static void quietlyResetPlanCacheMode(Statement statement) {
        try {
            statement.execute("SET plan_cache_mode = auto");
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
