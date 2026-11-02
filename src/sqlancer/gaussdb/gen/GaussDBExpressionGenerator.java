package sqlancer.gaussdb.gen;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.gen.TypedExpressionGenerator;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.ast.GaussDBAggregate;
import sqlancer.gaussdb.ast.GaussDBBetweenOperation;
import sqlancer.gaussdb.ast.GaussDBBinaryOperation;
import sqlancer.gaussdb.ast.GaussDBBinaryOperation.Operator;
import sqlancer.gaussdb.ast.GaussDBCaseOperation;
import sqlancer.gaussdb.ast.GaussDBCastOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBExtractOperation;
import sqlancer.gaussdb.ast.GaussDBFunction;
import sqlancer.gaussdb.ast.GaussDBInOperation;
import sqlancer.gaussdb.ast.GaussDBOrderByTerm;
import sqlancer.gaussdb.ast.GaussDBUnaryOperation;

public final class GaussDBExpressionGenerator
        extends TypedExpressionGenerator<GaussDBExpression, GaussDBColumn, GaussDBDataType> {

    private final GaussDBGlobalState globalState;
    private String tableAlias;

    public GaussDBExpressionGenerator(GaussDBGlobalState globalState) {
        this.globalState = globalState;
    }

    public GaussDBExpressionGenerator setTableAlias(String tableAlias) {
        this.tableAlias = tableAlias;
        return this;
    }

    @Override
    protected GaussDBExpression generateExpression(GaussDBDataType type, int depth) {
        if (depth >= globalState.getOptions().getMaxExpressionDepth() || Randomly.getBoolean()) {
            return generateLeafNode(type);
        }
        if (Randomly.getBooleanWithSmallProbability()) {
            return generateFunction(type, depth);
        }
        switch (type) {
        case BOOLEAN:
            return generateBooleanExpression(depth);
        case INT:
        case DECIMAL:
        case FLOAT:
        case REAL:
            return generateNumericExpression(type, depth);
        case TEXT:
            return generateTextExpression(depth);
        case DATE:
        case TIMESTAMP:
        case TIMESTAMPTZ:
        case TIME:
        case INTERVAL:
            return generateTemporalExpression(type, depth);
        default:
            throw new AssertionError(type);
        }
    }

    private GaussDBExpression generateBooleanExpression(int depth) {
        switch (Randomly.fromOptions(0, 1, 2, 3, 4, 5)) {
        case 0:
            return new GaussDBUnaryOperation(generateExpression(GaussDBDataType.BOOLEAN, depth + 1),
                    GaussDBUnaryOperation.Operator.NOT, GaussDBDataType.BOOLEAN);
        case 1:
            return generateComparison(depth);
        case 2:
            return generateLogicalOperation(depth);
        case 3:
            return generateNullCheck(depth);
        case 4:
            return generateInOperation(depth);
        case 5:
            return generateBetweenOperation(depth);
        default:
            throw new AssertionError("Unsupported boolean expression");
        }
    }

    private GaussDBExpression generateNumericExpression(GaussDBDataType type, int depth) {
        switch (Randomly.fromOptions(0, 1, 2)) {
        case 0:
            return generateArithmeticOperation(type, depth);
        case 1:
            return generateCastOperation(type, depth);
        case 2:
            return generateCaseOperation(type, depth);
        default:
            throw new AssertionError("Unsupported numeric expression");
        }
    }

    private GaussDBExpression generateTextExpression(int depth) {
        switch (Randomly.fromOptions(0, 1, 2, 3)) {
        case 0:
            return generateLeafNode(GaussDBDataType.TEXT);
        case 1:
            return generateFunction(GaussDBDataType.TEXT, depth);
        case 2:
            return generateCaseOperation(GaussDBDataType.TEXT, depth);
        case 3:
            return new GaussDBCastOperation(generateExpression(getRandomNumericType(), depth + 1),
                    GaussDBDataType.TEXT);
        default:
            throw new AssertionError("Unsupported text expression");
        }
    }

    private GaussDBExpression generateFunction(GaussDBDataType type, int depth) {
        if (Randomly.getBooleanWithSmallProbability()) {
            return generatePolymorphicFunction(type, depth);
        }
        switch (type) {
        case INT:
            switch (Randomly.fromOptions(0, 1, 2, 3, 4)) {
            case 0:
                return new GaussDBFunction("abs", List.of(generateExpression(type, depth + 1)), type);
            case 1:
                return new GaussDBFunction("length", List.of(generateExpression(GaussDBDataType.TEXT, depth + 1)), type);
            case 2:
                return new GaussDBFunction("strpos",
                        List.of(generateExpression(GaussDBDataType.TEXT, depth + 1),
                                generateExpression(GaussDBDataType.TEXT, depth + 1)),
                        type);
            case 3:
                return new GaussDBFunction("ascii", List.of(generateExpression(GaussDBDataType.TEXT, depth + 1)), type);
            case 4:
                return new GaussDBFunction("sign", List.of(generateExpression(type, depth + 1)), type);
            default:
                throw new AssertionError("Unsupported int function");
            }
        case TEXT:
            switch (Randomly.fromOptions(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)) {
            case 0:
                return new GaussDBFunction("lower", List.of(generateExpression(type, depth + 1)), type);
            case 1:
                return new GaussDBFunction("upper", List.of(generateExpression(type, depth + 1)), type);
            case 2:
                return new GaussDBFunction("btrim", List.of(generateExpression(type, depth + 1)), type);
            case 3:
                return new GaussDBFunction("concat",
                        List.of(generateExpression(type, depth + 1), generateExpression(type, depth + 1)), type);
            case 4:
                return new GaussDBFunction("ltrim", List.of(generateExpression(type, depth + 1)), type);
            case 5:
                return new GaussDBFunction("rtrim", List.of(generateExpression(type, depth + 1)), type);
            case 6:
                return new GaussDBFunction("reverse", List.of(generateExpression(type, depth + 1)), type);
            case 7:
                return new GaussDBFunction("replace",
                        List.of(generateExpression(type, depth + 1), generateExpression(type, depth + 1),
                                generateExpression(type, depth + 1)),
                        type);
            case 8:
                return new GaussDBFunction("substr",
                        List.of(generateExpression(type, depth + 1),
                                GaussDBConstant.createIntConstant(globalState.getRandomly().getInteger(1, 5)),
                                GaussDBConstant.createIntConstant(globalState.getRandomly().getInteger(1, 5))),
                        type);
            case 9:
                return new GaussDBFunction("regexp_replace",
                        List.of(generateExpression(type, depth + 1),
                                GaussDBConstant.createTextConstant("[a-zA-Z0-9]+"),
                                generateExpression(type, depth + 1)),
                        type);
            default:
                throw new AssertionError("Unsupported text function");
            }
        case BOOLEAN:
            return generatePolymorphicFunction(type, depth);
        case DECIMAL:
        case FLOAT:
        case REAL:
            if (Randomly.getBooleanWithSmallProbability()) {
                GaussDBDataType temporalType = getRandomTemporalType();
                String temporalPart = getRandomTemporalPart(temporalType);
                if (Randomly.getBoolean()) {
                    return new GaussDBExtractOperation(temporalPart,
                            generateExpression(temporalType, depth + 1));
                }
                return new GaussDBFunction("date_part",
                        List.of(GaussDBConstant.createTextConstant(temporalPart),
                                generateExpression(temporalType, depth + 1)),
                        GaussDBDataType.DECIMAL);
            }
            String numericFunc = Randomly.fromOptions("abs", "ceil", "floor", "round", "trunc");
            return new GaussDBFunction(numericFunc, List.of(generateExpression(type, depth + 1)), type);
        case DATE:
        case TIMESTAMP:
        case TIMESTAMPTZ:
        case TIME:
        case INTERVAL:
            return generateTemporalFunction(type, depth);
        default:
            throw new AssertionError(type);
        }
    }

    private GaussDBExpression generatePolymorphicFunction(GaussDBDataType type, int depth) {
        String fn;
        if (type == GaussDBDataType.BOOLEAN && globalState.getDbmsSpecificOptions() != null
                && globalState.getDbmsSpecificOptions().bypassBooleanTestRangeBug) {
            fn = Randomly.fromOptions("greatest", "least");
        } else {
            fn = Randomly.fromOptions("coalesce", "nvl", "nullif", "greatest", "least");
        }
        return new GaussDBFunction(fn,
                List.of(generateExpression(type, depth + 1), generateExpression(type, depth + 1)), type);
    }

    private GaussDBExpression generateTemporalExpression(GaussDBDataType type, int depth) {
        switch (Randomly.fromOptions(0, 1, 2, 3)) {
        case 0:
            return generateLeafNode(type);
        case 1:
            return generateTemporalArithmetic(type, depth);
        case 2:
            return generateTemporalFunction(type, depth);
        case 3:
            return generateCaseOperation(type, depth);
        default:
            throw new AssertionError("Unsupported temporal expression");
        }
    }

    private GaussDBExpression generateTemporalArithmetic(GaussDBDataType type, int depth) {
        switch (type) {
        case DATE:
            return generateTemporalFunction(GaussDBDataType.DATE, depth);
        case TIMESTAMP:
            if (Randomly.getBoolean()) {
                return new GaussDBBinaryOperation(generateExpression(GaussDBDataType.DATE, depth + 1),
                        generateExpression(GaussDBDataType.INTERVAL, depth + 1), Operator.PLUS,
                        GaussDBDataType.TIMESTAMP);
            }
            return new GaussDBBinaryOperation(generateExpression(GaussDBDataType.TIMESTAMP, depth + 1),
                    generateExpression(GaussDBDataType.INTERVAL, depth + 1), Operator.MINUS,
                    GaussDBDataType.TIMESTAMP);
        case INTERVAL:
            if (Randomly.getBoolean()) {
                return new GaussDBBinaryOperation(generateExpression(GaussDBDataType.TIMESTAMP, depth + 1),
                        generateExpression(GaussDBDataType.TIMESTAMP, depth + 1), Operator.MINUS,
                        GaussDBDataType.INTERVAL);
            }
            return new GaussDBBinaryOperation(generateExpression(GaussDBDataType.INTERVAL, depth + 1),
                    generateExpression(GaussDBDataType.INTERVAL, depth + 1), Operator.getRandomArithmetic(),
                    GaussDBDataType.INTERVAL);
        case TIME:
        case TIMESTAMPTZ:
            return generateLeafNode(type);
        default:
            throw new AssertionError(type);
        }
    }

    private GaussDBExpression generateTemporalFunction(GaussDBDataType type, int depth) {
        switch (type) {
        case TIMESTAMP:
            return new GaussDBFunction("date_trunc",
                    List.of(GaussDBConstant.createTextConstant("day"),
                            generateExpression(GaussDBDataType.TIMESTAMP, depth + 1)),
                    GaussDBDataType.TIMESTAMP);
        case TIMESTAMPTZ:
            return new GaussDBFunction("date_trunc",
                    List.of(GaussDBConstant.createTextConstant("day"),
                            generateExpression(GaussDBDataType.TIMESTAMPTZ, depth + 1)),
                    GaussDBDataType.TIMESTAMPTZ);
        case TIME:
            return generateLeafNode(GaussDBDataType.TIME);
        case INTERVAL:
            switch (Randomly.fromOptions(0, 1, 2, 3)) {
            case 0:
                return new GaussDBFunction("age",
                        List.of(generateExpression(GaussDBDataType.TIMESTAMP, depth + 1),
                                generateExpression(GaussDBDataType.TIMESTAMP, depth + 1)),
                        GaussDBDataType.INTERVAL);
            case 1:
                return new GaussDBFunction("justify_interval",
                        List.of(generateExpression(GaussDBDataType.INTERVAL, depth + 1)),
                        GaussDBDataType.INTERVAL);
            case 2:
                return new GaussDBFunction("justify_hours",
                        List.of(generateExpression(GaussDBDataType.INTERVAL, depth + 1)),
                        GaussDBDataType.INTERVAL);
            case 3:
                return new GaussDBFunction("justify_days",
                        List.of(generateExpression(GaussDBDataType.INTERVAL, depth + 1)),
                        GaussDBDataType.INTERVAL);
            default:
                throw new AssertionError("Unsupported interval function");
            }
        case DATE:
            String dateString = LocalDate.ofEpochDay(globalState.getRandomly().getInteger(0, 3650))
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
            return new GaussDBFunction("to_date",
                    List.of(GaussDBConstant.createTextConstant(dateString),
                            GaussDBConstant.createTextConstant("YYYY-MM-DD")),
                    GaussDBDataType.DATE);
        default:
            throw new AssertionError(type);
        }
    }

    private GaussDBExpression generateComparison(int depth) {
        GaussDBDataType type = getComparisonOperandType();
        GaussDBExpression left = generateExpression(type, depth + 1);
        GaussDBExpression right = generateExpression(type, depth + 1);
        return new GaussDBBinaryOperation(left, right, Operator.getRandomComparison(), GaussDBDataType.BOOLEAN);
    }

    private GaussDBExpression generateLogicalOperation(int depth) {
        GaussDBExpression left = generateExpression(GaussDBDataType.BOOLEAN, depth + 1);
        GaussDBExpression right = generateExpression(GaussDBDataType.BOOLEAN, depth + 1);
        return new GaussDBBinaryOperation(left, right, Operator.getRandomLogical(), GaussDBDataType.BOOLEAN);
    }

    private GaussDBExpression generateNullCheck(int depth) {
        GaussDBDataType type = getComparisonOperandType();
        return new GaussDBUnaryOperation(generateExpression(type, depth + 1),
                GaussDBUnaryOperation.Operator.getRandomNullCheck(), GaussDBDataType.BOOLEAN);
    }

    private GaussDBExpression generateInOperation(int depth) {
        GaussDBDataType type = getComparisonOperandType();
        GaussDBExpression expression = generateExpression(type, depth + 1);
        List<GaussDBExpression> list = generateExpressions(type, Randomly.smallNumber() + 1, depth + 1);
        return new GaussDBInOperation(expression, list, Randomly.getBoolean());
    }

    private GaussDBExpression generateBetweenOperation(int depth) {
        GaussDBDataType type = getComparisonOperandType();
        GaussDBExpression expression = generateExpression(type, depth + 1);
        GaussDBExpression left = generateExpression(type, depth + 1);
        GaussDBExpression right = generateExpression(type, depth + 1);
        return new GaussDBBetweenOperation(expression, left, right, Randomly.getBoolean(), Randomly.getBoolean());
    }

    private GaussDBDataType getComparisonOperandType() {
        GaussDBDataType type = getRandomType();
        if (type == GaussDBDataType.BOOLEAN && globalState.getDbmsSpecificOptions() != null
                && globalState.getDbmsSpecificOptions().bypassBooleanTestRangeBug) {
            return Randomly.fromOptions(GaussDBDataType.INT, GaussDBDataType.DECIMAL, GaussDBDataType.TEXT,
                    GaussDBDataType.DATE, GaussDBDataType.TIMESTAMP);
        }
        return type;
    }

    private GaussDBExpression generateArithmeticOperation(GaussDBDataType type, int depth) {
        GaussDBExpression left = generateExpression(type, depth + 1);
        GaussDBExpression right = generateExpression(type, depth + 1);
        return new GaussDBBinaryOperation(left, right, Operator.getRandomArithmetic(), type);
    }

    private GaussDBExpression generateCastOperation(GaussDBDataType type, int depth) {
        GaussDBDataType sourceType = Randomly.getBooleanWithSmallProbability()
                ? Randomly.fromOptions(GaussDBDataType.BOOLEAN, GaussDBDataType.TEXT)
                : getRandomNumericType();
        return new GaussDBCastOperation(generateExpression(sourceType, depth + 1), type);
    }

    public GaussDBOrderByTerm generateOrderByTerm(GaussDBExpression expression) {
        return new GaussDBOrderByTerm(expression, GaussDBOrderByTerm.Order.getRandom(),
                GaussDBOrderByTerm.NullsOrder.getRandom());
    }

    private GaussDBExpression generateCaseOperation(GaussDBDataType type, int depth) {
        List<GaussDBExpression> conditions = new ArrayList<>();
        List<GaussDBExpression> results = new ArrayList<>();
        int branches = Randomly.smallNumber() + 1;
        for (int i = 0; i < branches; i++) {
            conditions.add(generateExpression(GaussDBDataType.BOOLEAN, depth + 1));
            results.add(generateExpression(type, depth + 1));
        }
        GaussDBExpression elseExpression = Randomly.getBoolean() ? generateExpression(type, depth + 1) : null;
        return new GaussDBCaseOperation(conditions, results, elseExpression, type);
    }

    private GaussDBDataType getRandomNumericType() {
        List<GaussDBDataType> numericTypes = new ArrayList<>();
        numericTypes.add(GaussDBDataType.INT);
        numericTypes.add(GaussDBDataType.DECIMAL);
        numericTypes.add(GaussDBDataType.FLOAT);
        numericTypes.add(GaussDBDataType.REAL);
        return Randomly.fromList(numericTypes);
    }

    private GaussDBDataType getRandomTemporalType() {
        List<GaussDBDataType> temporalTypes = new ArrayList<>();
        temporalTypes.add(GaussDBDataType.DATE);
        temporalTypes.add(GaussDBDataType.TIMESTAMP);
        temporalTypes.add(GaussDBDataType.TIMESTAMPTZ);
        temporalTypes.add(GaussDBDataType.TIME);
        temporalTypes.add(GaussDBDataType.INTERVAL);
        return Randomly.fromList(temporalTypes);
    }

    private String getRandomTemporalPart(GaussDBDataType temporalType) {
        List<String> temporalParts = new ArrayList<>();
        switch (temporalType) {
        case DATE:
            temporalParts.add("year");
            temporalParts.add("month");
            temporalParts.add("day");
            break;
        case TIME:
            temporalParts.add("hour");
            temporalParts.add("minute");
            temporalParts.add("second");
            break;
        case TIMESTAMP:
        case TIMESTAMPTZ:
        case INTERVAL:
            temporalParts.add("year");
            temporalParts.add("month");
            temporalParts.add("day");
            temporalParts.add("hour");
            temporalParts.add("minute");
            temporalParts.add("second");
            break;
        default:
            throw new AssertionError(temporalType);
        }
        return Randomly.fromList(temporalParts);
    }

    @Override
    public GaussDBExpression generateConstant(GaussDBDataType type) {
        return generateConstant(globalState.getRandomly(), type);
    }

    public static GaussDBExpression generateConstant(Randomly r, GaussDBDataType type) {
        switch (type) {
        case INT:
            return GaussDBConstant.createIntConstant(r.getInteger(-1000, 1000));
        case BOOLEAN:
            return GaussDBConstant.createBooleanConstant(Randomly.getBoolean());
        case TEXT:
            return GaussDBConstant.createTextConstant(r.getString());
        case DECIMAL:
            return GaussDBConstant.createDecimalConstant(BigDecimal.valueOf(r.getInteger(-1000, 1000)));
        case FLOAT:
        case REAL:
            return GaussDBConstant.createDecimalConstant(BigDecimal.valueOf(r.getInteger(-100, 100)));
        case DATE:
            return GaussDBConstant.createDateConstant(
                    LocalDate.ofEpochDay(r.getInteger(0, 3650)).format(DateTimeFormatter.ISO_LOCAL_DATE));
        case TIMESTAMP:
            return GaussDBConstant.createTimestampConstant(LocalDateTime
                    .ofEpochSecond(r.getInteger(0, 31536000), 0, ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        case TIMESTAMPTZ:
            return GaussDBConstant.createTimestamptzConstant(LocalDateTime
                    .ofEpochSecond(r.getInteger(0, 31536000), 0, ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    + "+08");
        case TIME:
            return GaussDBConstant.createTimeConstant(
                    String.format("%02d:%02d:%02d", r.getInteger(0, 23), r.getInteger(0, 59), r.getInteger(0, 59)));
        case INTERVAL:
            return GaussDBConstant.createIntervalConstant(
                    String.format("%d days %d hours %d minutes", r.getInteger(0, 365), r.getInteger(0, 23),
                            r.getInteger(0, 59)));
        default:
            throw new AssertionError(type);
        }
    }

    @Override
    protected GaussDBExpression generateColumn(GaussDBDataType type) {
        List<GaussDBColumn> matchingColumns = columns.stream().filter(c -> c.getType() == type)
                .collect(Collectors.toList());
        if (matchingColumns.isEmpty()) {
            return generateConstant(type);
        }
        return new GaussDBColumnReference(Randomly.fromList(matchingColumns), tableAlias);
    }

    @Override
    protected GaussDBDataType getRandomType() {
        if (columns.isEmpty() || Randomly.getBooleanWithRatherLowProbability()) {
            return GaussDBDataType.getRandomType();
        }
        return Randomly.fromList(columns).getType();
    }

    @Override
    protected boolean canGenerateColumnOfType(GaussDBDataType type) {
        return columns.stream().anyMatch(c -> c.getType() == type);
    }

    @Override
    public GaussDBExpression generatePredicate() {
        return generateExpression(GaussDBDataType.BOOLEAN);
    }

    @Override
    public GaussDBExpression negatePredicate(GaussDBExpression predicate) {
        return new GaussDBUnaryOperation(predicate, GaussDBUnaryOperation.Operator.NOT, GaussDBDataType.BOOLEAN);
    }

    @Override
    public GaussDBExpression isNull(GaussDBExpression expr) {
        return new GaussDBUnaryOperation(expr, GaussDBUnaryOperation.Operator.IS_NULL, GaussDBDataType.BOOLEAN);
    }

    public GaussDBExpression generateAggregate(GaussDBDataType type) {
        GaussDBAggregate.Function function = GaussDBAggregate.Function.getRandom();
        List<GaussDBExpression> arguments = new ArrayList<>();
        arguments.add(generateExpression(type));
        GaussDBDataType resultType = function == GaussDBAggregate.Function.COUNT ? GaussDBDataType.INT : type;
        return new GaussDBAggregate(function, arguments, resultType);
    }

}
