package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

/**
 * Procedural Language (Oracle PL/SQL Compatibility) Oracle for GaussDB.
 *
 * <p>
 * Tests GaussDB's Oracle-mode procedural engine (PL/SQL / plpgsql) by generating stored functions with explicit cursors,
 * cursor FOR-loops, dynamic SQL (EXECUTE IMMEDIATE), and IF-ELSE control flow, then verifies procedural outcomes
 * against declarative CBO SQL aggregates.
 * </p>
 */
public class GaussDBPLSQLOracle implements TestOracle<GaussDBGlobalState> {

    private static final String TABLE_ALIAS = "t";
    private static final String TEMP_FN_PREFIX = "_plsql_test_fn_";

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBPLSQLReproducer reproducer;
    private String lastQueryString;

    public GaussDBPLSQLOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public enum PLSQLMode {
        CURSOR_LOOP,
        DYNAMIC_SQL,
        CONDITIONAL_BRANCH
    }

    public static final class PLSQLBundle {

        private final PLSQLMode mode;
        private final String functionName;
        private final String createFunctionSql;
        private final String callFunctionSql;
        private final String declarativeSql;
        private final String dropFunctionSql;

        public PLSQLBundle(PLSQLMode mode, String functionName, String createFunctionSql, String callFunctionSql,
                String declarativeSql, String dropFunctionSql) {
            this.mode = mode;
            this.functionName = functionName;
            this.createFunctionSql = createFunctionSql;
            this.callFunctionSql = callFunctionSql;
            this.declarativeSql = declarativeSql;
            this.dropFunctionSql = dropFunctionSql;
        }

        public PLSQLMode getMode() {
            return mode;
        }

        public String getFunctionName() {
            return functionName;
        }

        public String getCreateFunctionSql() {
            return createFunctionSql;
        }

        public String getCallFunctionSql() {
            return callFunctionSql;
        }

        public String getDeclarativeSql() {
            return declarativeSql;
        }

        public String getDropFunctionSql() {
            return dropFunctionSql;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());

        List<GaussDBColumn> numericCols = table.getColumns().stream()
                .filter(c -> c.getType() == GaussDBDataType.INT || c.getType() == GaussDBDataType.DECIMAL)
                .collect(Collectors.toList());

        if (numericCols.isEmpty()) {
            throw new IgnoreMeException();
        }

        GaussDBColumn col = Randomly.fromList(numericCols);
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(table.getColumns());
        generator.setTableAlias(TABLE_ALIAS);
        GaussDBExpression wherePredicate = generator.generateExpression(GaussDBDataType.BOOLEAN);

        String fnName = TEMP_FN_PREFIX + System.nanoTime();
        PLSQLBundle bundle;
        switch (Randomly.fromOptions(0, 1, 2)) {
        case 0:
            bundle = buildCursorLoopBundle(fnName, table, col, wherePredicate);
            break;
        case 1:
            bundle = buildDynamicSQLBundle(fnName, table, col, wherePredicate);
            break;
        case 2:
            bundle = buildConditionalBranchBundle(fnName, table, col, wherePredicate);
            break;
        default:
            throw new AssertionError();
        }

        verifyPLSQLBundle(bundle);
    }

    public static PLSQLBundle buildCursorLoopBundle(String fnName, GaussDBTable table, GaussDBColumn col,
            GaussDBExpression wherePredicate) {
        String predStr = GaussDBToStringVisitor.asString(wherePredicate);
        String colName = col.getName();
        String tbl = table.getName();

        String createSql = String.format(
                "CREATE OR REPLACE FUNCTION %s() RETURNS TABLE(cnt INT, total NUMERIC) AS $$%n"
                        + "DECLARE%n"
                        + "    v_cnt INT := 0;%n"
                        + "    v_sum NUMERIC := 0;%n"
                        + "    CURSOR cur IS SELECT %s FROM %s %s WHERE %s;%n"
                        + "BEGIN%n"
                        + "    FOR r IN cur LOOP%n"
                        + "        v_cnt := v_cnt + 1;%n"
                        + "        v_sum := v_sum + COALESCE(r.%s, 0);%n"
                        + "    END LOOP;%n"
                        + "    RETURN QUERY SELECT v_cnt, v_sum;%n"
                        + "END;%n"
                        + "$$ LANGUAGE plpgsql;",
                fnName, colName, tbl, TABLE_ALIAS, predStr, colName);

        String callSql = String.format("SELECT cnt, total FROM %s()", fnName);
        String declSql = String.format("SELECT COUNT(*), COALESCE(SUM(%s), 0) FROM %s %s WHERE %s",
                colName, tbl, TABLE_ALIAS, predStr);
        String dropSql = String.format("DROP FUNCTION IF EXISTS %s()", fnName);

        return new PLSQLBundle(PLSQLMode.CURSOR_LOOP, fnName, createSql, callSql, declSql, dropSql);
    }

    public static PLSQLBundle buildDynamicSQLBundle(String fnName, GaussDBTable table, GaussDBColumn col,
            GaussDBExpression wherePredicate) {
        String predStr = GaussDBToStringVisitor.asString(wherePredicate);
        String colName = col.getName();
        String tbl = table.getName();

        String dynamicQuery = String.format("SELECT COUNT(*), COALESCE(SUM(%s), 0) FROM %s %s WHERE %s",
                colName, tbl, TABLE_ALIAS, predStr).replace("'", "''");

        String createSql = String.format(
                "CREATE OR REPLACE FUNCTION %s() RETURNS TABLE(cnt INT, total NUMERIC) AS $$%n"
                        + "DECLARE%n"
                        + "    v_cnt INT := 0;%n"
                        + "    v_sum NUMERIC := 0;%n"
                        + "BEGIN%n"
                        + "    EXECUTE IMMEDIATE '%s' INTO v_cnt, v_sum;%n"
                        + "    RETURN QUERY SELECT v_cnt, v_sum;%n"
                        + "END;%n"
                        + "$$ LANGUAGE plpgsql;",
                fnName, dynamicQuery);

        String callSql = String.format("SELECT cnt, total FROM %s()", fnName);
        String declSql = String.format("SELECT COUNT(*), COALESCE(SUM(%s), 0) FROM %s %s WHERE %s",
                colName, tbl, TABLE_ALIAS, predStr);
        String dropSql = String.format("DROP FUNCTION IF EXISTS %s()", fnName);

        return new PLSQLBundle(PLSQLMode.DYNAMIC_SQL, fnName, createSql, callSql, declSql, dropSql);
    }

    public static PLSQLBundle buildConditionalBranchBundle(String fnName, GaussDBTable table, GaussDBColumn col,
            GaussDBExpression wherePredicate) {
        String predStr = GaussDBToStringVisitor.asString(wherePredicate);
        String colName = col.getName();
        String tbl = table.getName();

        String createSql = String.format(
                "CREATE OR REPLACE FUNCTION %s() RETURNS TABLE(cnt INT, total NUMERIC) AS $$%n"
                        + "DECLARE%n"
                        + "    v_cnt INT := 0;%n"
                        + "    v_sum NUMERIC := 0;%n"
                        + "BEGIN%n"
                        + "    SELECT COUNT(*), COALESCE(SUM(%s), 0) INTO v_cnt, v_sum FROM %s %s WHERE %s;%n"
                        + "    IF v_cnt > 0 THEN%n"
                        + "        v_sum := v_sum + 10;%n"
                        + "    ELSE%n"
                        + "        v_sum := -1;%n"
                        + "    END IF;%n"
                        + "    RETURN QUERY SELECT v_cnt, v_sum;%n"
                        + "END;%n"
                        + "$$ LANGUAGE plpgsql;",
                fnName, colName, tbl, TABLE_ALIAS, predStr);

        String callSql = String.format("SELECT cnt, total FROM %s()", fnName);
        String declSql = String.format(
                "SELECT COUNT(*), CASE WHEN COUNT(*) > 0 THEN COALESCE(SUM(%s), 0) + 10 ELSE -1 END FROM %s %s WHERE %s",
                colName, tbl, TABLE_ALIAS, predStr);
        String dropSql = String.format("DROP FUNCTION IF EXISTS %s()", fnName);

        return new PLSQLBundle(PLSQLMode.CONDITIONAL_BRANCH, fnName, createSql, callSql, declSql, dropSql);
    }

    private void verifyPLSQLBundle(PLSQLBundle bundle) throws SQLException {
        this.lastQueryString = bundle.getDeclarativeSql();
        SQLConnection con = state.getConnection();

        try (Statement statement = con.createStatement()) {
            List<String> declRows;
            try {
                declRows = executeAndGetRows(statement, bundle.getDeclarativeSql());
            } catch (SQLException e) {
                if (errors.errorIsExpected(e.getMessage())) {
                    return;
                }
                throw e;
            }

            try {
                statement.execute(bundle.getCreateFunctionSql());
            } catch (SQLException e) {
                if (errors.errorIsExpected(e.getMessage())) {
                    return;
                }
                this.reproducer = new GaussDBPLSQLReproducer(bundle);
                throw new AssertionError(String.format(
                        "PL/SQL Stored Function Compilation Failed!%nCreate SQL:%n%s%nError: %s",
                        bundle.getCreateFunctionSql(), e.getMessage()), e);
            }

            List<String> plsqlRows;
            try {
                plsqlRows = executeAndGetRows(statement, bundle.getCallFunctionSql());
            } catch (SQLException e) {
                if (errors.errorIsExpected(e.getMessage())) {
                    return;
                }
                this.reproducer = new GaussDBPLSQLReproducer(bundle);
                throw new AssertionError(String.format(
                        "PL/SQL Function Execution Failed while Declarative Succeeded!%nCall SQL: %s%nError: %s",
                        bundle.getCallFunctionSql(), e.getMessage()), e);
            } finally {
                quietlyDropFunction(statement, bundle.getDropFunctionSql());
            }

            if (!areEquivalent(declRows, plsqlRows)) {
                this.reproducer = new GaussDBPLSQLReproducer(bundle);
                throw new AssertionError(String.format(
                        "PL/SQL vs Declarative SQL Invariance Violation (%s)!%nDeclarative Rows: %s%nPL/SQL Rows: %s%nDeclarative SQL: %s%nFunction Definition:%n%s",
                        bundle.getMode(), declRows, plsqlRows, bundle.getDeclarativeSql(), bundle.getCreateFunctionSql()));
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

    private void quietlyDropFunction(Statement statement, String dropSql) {
        try {
            statement.execute(dropSql);
        } catch (SQLException ignored) {
            // Ignore transient drop errors
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
