package sqlancer.gaussdb.gen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import sqlancer.Randomly;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBFunction;

/**
 * Helper for generating extreme boundary values and Oracle/PostgreSQL built-in functions (EuroSys'25).
 *
 * <p>
 * Implements the core insight of "Understanding and Detecting SQL Function Bugs" (EuroSys'25):
 * systematically injecting boundary values (0, NaN, -0.0, INT_MIN/MAX, empty strings, format masks,
 * leap-year dates) into function arguments triggers edge-case logic and crash bugs in DBMS compatibility layers.
 * </p>
 */
public final class GaussDBBoundaryHelper {

    private static final List<Long> INT_BOUNDARIES = Collections.unmodifiableList(Arrays.asList(
            0L, 1L, -1L,
            (long) Short.MAX_VALUE, (long) Short.MIN_VALUE,
            (long) Integer.MAX_VALUE, (long) Integer.MIN_VALUE,
            Long.MAX_VALUE, Long.MIN_VALUE
    ));

    private static final List<String> FLOAT_BOUNDARIES = Collections.unmodifiableList(Arrays.asList(
            "0.0", "-0.0", "1.0", "-1.0",
            "1e-307", "-1e-307", "1e308", "-1e308",
            "'NaN'::float", "'Infinity'::float", "'-Infinity'::float",
            "99999999999999999999999999999999999999.9999"
    ));

    private static final List<String> TEXT_BOUNDARIES = Collections.unmodifiableList(Arrays.asList(
            "", " ", "   ", "\t", "\n", "\r",
            "\\0", "'", "\\", "\\\\",
            "%s", "%d", "%x",
            "YYYY-MM-DD", "YYYY-MM-DD HH24:MI:SS",
            ".*", "^$", "(?=.*)", "[a-zA-Z0-9]+",
            "\uFFFF", "0", "-1", "NaN", "Infinity", "null", "NULL"
    ));

    private static final List<String> DATE_BOUNDARIES = Collections.unmodifiableList(Arrays.asList(
            "1970-01-01", "0001-01-01", "9999-12-31",
            "2024-02-29", "2000-02-29", "1900-02-28",
            "2026-11-05", "1999-12-31", "2038-01-19"
    ));

    private static final List<String> TIMESTAMP_BOUNDARIES = Collections.unmodifiableList(Arrays.asList(
            "1970-01-01 00:00:00", "9999-12-31 23:59:59", "0001-01-01 00:00:00",
            "2024-02-29 12:00:00", "2026-11-05 08:30:00"
    ));

    private static final List<String> TIME_BOUNDARIES = Collections.unmodifiableList(Arrays.asList(
            "00:00:00", "23:59:59", "12:00:00", "06:30:15"
    ));

    private static final List<String> INTERVAL_BOUNDARIES = Collections.unmodifiableList(Arrays.asList(
            "0 days", "1 days", "-1 days", "36500 days 23 hours 59 minutes 59 seconds"
    ));

    private GaussDBBoundaryHelper() {
    }

    public static GaussDBConstant generateBoundaryConstant(Randomly r, GaussDBDataType type) {
        switch (type) {
        case INT:
            return GaussDBConstant.createIntConstant(Randomly.fromList(INT_BOUNDARIES));
        case BOOLEAN:
            return Randomly.getBoolean() ? GaussDBConstant.createTrue() : GaussDBConstant.createFalse();
        case DECIMAL:
        case FLOAT:
        case REAL:
            return GaussDBConstant.createRawConstant(Randomly.fromList(FLOAT_BOUNDARIES), type);
        case TEXT:
            return GaussDBConstant.createTextConstant(Randomly.fromList(TEXT_BOUNDARIES));
        case DATE:
            return GaussDBConstant.createDateConstant(Randomly.fromList(DATE_BOUNDARIES));
        case TIMESTAMP:
            return GaussDBConstant.createTimestampConstant(Randomly.fromList(TIMESTAMP_BOUNDARIES));
        case TIMESTAMPTZ:
            return GaussDBConstant.createTimestamptzConstant(Randomly.fromList(TIMESTAMP_BOUNDARIES) + "+08");
        case TIME:
            return GaussDBConstant.createTimeConstant(Randomly.fromList(TIME_BOUNDARIES));
        case INTERVAL:
            return GaussDBConstant.createIntervalConstant(Randomly.fromList(INTERVAL_BOUNDARIES));
        default:
            return GaussDBConstant.createNullConstant();
        }
    }

    public static GaussDBExpression generateBoundaryFunction(Randomly r, GaussDBDataType returnType,
            List<GaussDBExpression> candidateExpressions) {
        return generateBoundaryFunction(r, returnType, candidateExpressions, sqlancer.gaussdb.GaussDBOptions.CompatibilityMode.A);
    }

    public static GaussDBExpression generateBoundaryFunction(Randomly r, GaussDBDataType returnType,
            List<GaussDBExpression> candidateExpressions, sqlancer.gaussdb.GaussDBOptions.CompatibilityMode mode) {
        List<GaussDBExpression> args = new ArrayList<>(candidateExpressions);
        boolean isMySQL = mode != null && mode.isMySQL();
        boolean isPG = mode != null && mode.isPostgres();

        switch (returnType) {
        case INT:
            if (isMySQL) {
                switch (r.getInteger(0, 7)) {
                case 0:
                    return new GaussDBFunction("abs", List.of(ensureArg(r, args, GaussDBDataType.INT)), returnType);
                case 1:
                    return new GaussDBFunction("sign", List.of(ensureArg(r, args, GaussDBDataType.INT)), returnType);
                case 2:
                    return new GaussDBFunction("length", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 3:
                    return new GaussDBFunction("ascii", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 4:
                    return new GaussDBFunction("locate",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT), ensureArg(r, args, GaussDBDataType.TEXT)),
                            returnType);
                case 5:
                    return new GaussDBFunction("mod",
                            List.of(ensureArg(r, args, GaussDBDataType.INT), ensureArg(r, args, GaussDBDataType.INT)),
                            returnType);
                case 6:
                    return new GaussDBFunction("ifnull",
                            List.of(ensureArg(r, args, GaussDBDataType.INT), ensureArg(r, args, GaussDBDataType.INT)),
                            returnType);
                default:
                    return new GaussDBFunction("ceiling", List.of(ensureArg(r, args, GaussDBDataType.DECIMAL)), returnType);
                }
            } else if (isPG) {
                switch (r.getInteger(0, 6)) {
                case 0:
                    return new GaussDBFunction("abs", List.of(ensureArg(r, args, GaussDBDataType.INT)), returnType);
                case 1:
                    return new GaussDBFunction("sign", List.of(ensureArg(r, args, GaussDBDataType.INT)), returnType);
                case 2:
                    return new GaussDBFunction("length", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 3:
                    return new GaussDBFunction("ascii", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 4:
                    return new GaussDBFunction("strpos",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT), ensureArg(r, args, GaussDBDataType.TEXT)),
                            returnType);
                case 5:
                    return new GaussDBFunction("mod",
                            List.of(ensureArg(r, args, GaussDBDataType.INT), ensureArg(r, args, GaussDBDataType.INT)),
                            returnType);
                default:
                    return new GaussDBFunction("coalesce",
                            List.of(ensureArg(r, args, GaussDBDataType.INT), ensureArg(r, args, GaussDBDataType.INT)),
                            returnType);
                }
            } else {
                switch (r.getInteger(0, 7)) {
                case 0:
                    return new GaussDBFunction("abs", List.of(ensureArg(r, args, GaussDBDataType.INT)), returnType);
                case 1:
                    return new GaussDBFunction("sign", List.of(ensureArg(r, args, GaussDBDataType.INT)), returnType);
                case 2:
                    return new GaussDBFunction("length", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 3:
                    return new GaussDBFunction("ascii", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 4:
                    return new GaussDBFunction("strpos",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT), ensureArg(r, args, GaussDBDataType.TEXT)),
                            returnType);
                case 5:
                    return new GaussDBFunction("bitand",
                            List.of(ensureArg(r, args, GaussDBDataType.INT), ensureArg(r, args, GaussDBDataType.INT)),
                            returnType);
                case 6:
                    return new GaussDBFunction("mod",
                            List.of(ensureArg(r, args, GaussDBDataType.INT), ensureArg(r, args, GaussDBDataType.INT)),
                            returnType);
                default:
                    return new GaussDBFunction("nvl",
                            List.of(ensureArg(r, args, GaussDBDataType.INT), ensureArg(r, args, GaussDBDataType.INT)),
                            returnType);
                }
            }
        case DECIMAL:
        case FLOAT:
        case REAL:
            if (isMySQL) {
                switch (r.getInteger(0, 5)) {
                case 0:
                    return new GaussDBFunction("abs", List.of(ensureArg(r, args, returnType)), returnType);
                case 1:
                    return new GaussDBFunction("ceil", List.of(ensureArg(r, args, returnType)), returnType);
                case 2:
                    return new GaussDBFunction("floor", List.of(ensureArg(r, args, returnType)), returnType);
                case 3:
                    return new GaussDBFunction("round",
                            List.of(ensureArg(r, args, returnType), GaussDBConstant.createIntConstant(r.getInteger(0, 4))),
                            returnType);
                case 4:
                    return new GaussDBFunction("truncate",
                            List.of(ensureArg(r, args, returnType), GaussDBConstant.createIntConstant(r.getInteger(0, 4))),
                            returnType);
                default:
                    return new GaussDBFunction("ifnull",
                            List.of(ensureArg(r, args, returnType), ensureArg(r, args, returnType)), returnType);
                }
            } else if (isPG) {
                switch (r.getInteger(0, 4)) {
                case 0:
                    return new GaussDBFunction("abs", List.of(ensureArg(r, args, returnType)), returnType);
                case 1:
                    return new GaussDBFunction("ceil", List.of(ensureArg(r, args, returnType)), returnType);
                case 2:
                    return new GaussDBFunction("floor", List.of(ensureArg(r, args, returnType)), returnType);
                case 3:
                    return new GaussDBFunction("round",
                            List.of(ensureArg(r, args, returnType), GaussDBConstant.createIntConstant(r.getInteger(0, 4))),
                            returnType);
                default:
                    return new GaussDBFunction("coalesce",
                            List.of(ensureArg(r, args, returnType), ensureArg(r, args, returnType)), returnType);
                }
            } else {
                switch (r.getInteger(0, 5)) {
                case 0:
                    return new GaussDBFunction("abs", List.of(ensureArg(r, args, returnType)), returnType);
                case 1:
                    return new GaussDBFunction("ceil", List.of(ensureArg(r, args, returnType)), returnType);
                case 2:
                    return new GaussDBFunction("floor", List.of(ensureArg(r, args, returnType)), returnType);
                case 3:
                    return new GaussDBFunction("round",
                            List.of(ensureArg(r, args, returnType), GaussDBConstant.createIntConstant(r.getInteger(0, 4))),
                            returnType);
                case 4:
                    return new GaussDBFunction("trunc",
                            List.of(ensureArg(r, args, returnType), GaussDBConstant.createIntConstant(r.getInteger(0, 4))),
                            returnType);
                default:
                    return new GaussDBFunction("nvl",
                            List.of(ensureArg(r, args, returnType), ensureArg(r, args, returnType)), returnType);
                }
            }
        case TEXT:
            if (isMySQL) {
                switch (r.getInteger(0, 8)) {
                case 0:
                    return new GaussDBFunction("lower", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 1:
                    return new GaussDBFunction("upper", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 2:
                    return new GaussDBFunction("trim", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 3:
                    return new GaussDBFunction("concat",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT), ensureArg(r, args, GaussDBDataType.TEXT)),
                            returnType);
                case 4:
                    return new GaussDBFunction("substring",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT),
                                    GaussDBConstant.createIntConstant(r.getInteger(1, 5)),
                                    GaussDBConstant.createIntConstant(r.getInteger(1, 5))),
                            returnType);
                case 5:
                    return new GaussDBFunction("replace",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT), ensureArg(r, args, GaussDBDataType.TEXT),
                                    ensureArg(r, args, GaussDBDataType.TEXT)),
                            returnType);
                case 6:
                    return new GaussDBFunction("reverse", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 7:
                    return new GaussDBFunction("left",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT), GaussDBConstant.createIntConstant(r.getInteger(1, 5))),
                            returnType);
                default:
                    return new GaussDBFunction("ifnull",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT), ensureArg(r, args, GaussDBDataType.TEXT)),
                            returnType);
                }
            } else if (isPG) {
                switch (r.getInteger(0, 7)) {
                case 0:
                    return new GaussDBFunction("lower", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 1:
                    return new GaussDBFunction("upper", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 2:
                    return new GaussDBFunction("btrim", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 3:
                    return new GaussDBFunction("concat",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT), ensureArg(r, args, GaussDBDataType.TEXT)),
                            returnType);
                case 4:
                    return new GaussDBFunction("substr",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT),
                                    GaussDBConstant.createIntConstant(r.getInteger(-2, 5)),
                                    GaussDBConstant.createIntConstant(r.getInteger(0, 5))),
                            returnType);
                case 5:
                    return new GaussDBFunction("replace",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT), ensureArg(r, args, GaussDBDataType.TEXT),
                                    ensureArg(r, args, GaussDBDataType.TEXT)),
                            returnType);
                case 6:
                    return new GaussDBFunction("reverse", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                default:
                    return new GaussDBFunction("coalesce",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT), ensureArg(r, args, GaussDBDataType.TEXT)),
                            returnType);
                }
            } else {
                switch (r.getInteger(0, 9)) {
                case 0:
                    return new GaussDBFunction("lower", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 1:
                    return new GaussDBFunction("upper", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 2:
                    return new GaussDBFunction("btrim", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 3:
                    return new GaussDBFunction("concat",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT), ensureArg(r, args, GaussDBDataType.TEXT)),
                            returnType);
                case 4:
                    return new GaussDBFunction("substr",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT),
                                    GaussDBConstant.createIntConstant(r.getInteger(-2, 5)),
                                    GaussDBConstant.createIntConstant(r.getInteger(0, 5))),
                            returnType);
                case 5:
                    return new GaussDBFunction("replace",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT), ensureArg(r, args, GaussDBDataType.TEXT),
                                    ensureArg(r, args, GaussDBDataType.TEXT)),
                            returnType);
                case 6:
                    return new GaussDBFunction("reverse", List.of(ensureArg(r, args, GaussDBDataType.TEXT)), returnType);
                case 7:
                    return new GaussDBFunction("nvl",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT), ensureArg(r, args, GaussDBDataType.TEXT)),
                            returnType);
                case 8:
                    return new GaussDBFunction("to_char", List.of(ensureArg(r, args, GaussDBDataType.INT)), returnType);
                default:
                    return new GaussDBFunction("coalesce",
                            List.of(ensureArg(r, args, GaussDBDataType.TEXT), ensureArg(r, args, GaussDBDataType.TEXT)),
                            returnType);
                }
            }
        case DATE:
            if (isMySQL) {
                switch (r.getInteger(0, 2)) {
                case 0:
                    return new GaussDBFunction("last_day", List.of(ensureArg(r, args, GaussDBDataType.DATE)), returnType);
                case 1:
                    return new GaussDBFunction("curdate", Collections.emptyList(), returnType);
                default:
                    return new GaussDBFunction("ifnull",
                            List.of(ensureArg(r, args, GaussDBDataType.DATE), ensureArg(r, args, GaussDBDataType.DATE)),
                            returnType);
                }
            } else if (isPG) {
                switch (r.getInteger(0, 1)) {
                case 0:
                    return new GaussDBFunction("date_trunc",
                            List.of(GaussDBConstant.createTextConstant("month"),
                                    ensureArg(r, args, GaussDBDataType.DATE)),
                            returnType);
                default:
                    return new GaussDBFunction("coalesce",
                            List.of(ensureArg(r, args, GaussDBDataType.DATE), ensureArg(r, args, GaussDBDataType.DATE)),
                            returnType);
                }
            } else {
                switch (r.getInteger(0, 3)) {
                case 0:
                    return new GaussDBFunction("add_months",
                            List.of(ensureArg(r, args, GaussDBDataType.DATE),
                                    GaussDBConstant.createIntConstant(r.getInteger(-12, 12))),
                            returnType);
                case 1:
                    return new GaussDBFunction("last_day", List.of(ensureArg(r, args, GaussDBDataType.DATE)), returnType);
                case 2:
                    return new GaussDBFunction("nvl",
                            List.of(ensureArg(r, args, GaussDBDataType.DATE), ensureArg(r, args, GaussDBDataType.DATE)),
                            returnType);
                default:
                    return new GaussDBFunction("date_trunc",
                            List.of(GaussDBConstant.createTextConstant("month"),
                                    ensureArg(r, args, GaussDBDataType.DATE)),
                            returnType);
                }
            }
        default:
            String fallbackNullFn = isMySQL ? "ifnull" : "coalesce";
            return new GaussDBFunction(fallbackNullFn,
                    List.of(ensureArg(r, args, returnType), ensureArg(r, args, returnType)), returnType);
        }
    }

    private static GaussDBExpression ensureArg(Randomly r, List<GaussDBExpression> args, GaussDBDataType type) {
        if (!args.isEmpty() && Randomly.getBoolean()) {
            for (GaussDBExpression expr : args) {
                if (expr.getExpressionType() == type) {
                    return expr;
                }
            }
        }
        return generateBoundaryConstant(r, type);
    }

}
