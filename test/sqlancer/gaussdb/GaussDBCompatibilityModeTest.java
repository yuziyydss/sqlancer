package sqlancer.gaussdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.Randomly;
import sqlancer.gaussdb.GaussDBOptions.CompatibilityMode;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.gen.GaussDBBoundaryHelper;
import sqlancer.gaussdb.gen.GaussDBTableGenerator;
import sqlancer.gaussdb.oracle.GaussDBPLSQLOracle;
import sqlancer.gaussdb.oracle.GaussDBPLSQLOracle.PLSQLBundle;

class GaussDBCompatibilityModeTest {

    @Test
    void testCompatibilityModeEnum() {
        CompatibilityMode modeA = CompatibilityMode.A;
        assertTrue(modeA.isOracle());
        assertFalse(modeA.isMySQL());
        assertFalse(modeA.isPostgres());

        CompatibilityMode modeB = CompatibilityMode.B;
        assertFalse(modeB.isOracle());
        assertTrue(modeB.isMySQL());
        assertFalse(modeB.isPostgres());

        CompatibilityMode modePG = CompatibilityMode.PG;
        assertFalse(modePG.isOracle());
        assertFalse(modePG.isMySQL());
        assertTrue(modePG.isPostgres());
    }

    @Test
    void testTableGeneratorMySQLMode() {
        GaussDBTableGenerator genB = new GaussDBTableGenerator("t_mysql", CompatibilityMode.B);
        String sqlB = genB.generate().getQueryString();
        assertNotNull(sqlB);
        assertTrue(sqlB.startsWith("CREATE TABLE IF NOT EXISTS t_mysql("));
    }

    @Test
    void testTableGeneratorPostgresMode() {
        GaussDBTableGenerator genPG = new GaussDBTableGenerator("t_pg", CompatibilityMode.PG);
        String sqlPG = genPG.generate().getQueryString();
        assertNotNull(sqlPG);
        assertTrue(sqlPG.startsWith("CREATE TABLE IF NOT EXISTS t_pg("));
    }

    @Test
    void testBoundaryFunctionsAcrossModes() {
        Randomly r = new Randomly();
        List<GaussDBExpression> emptyArgs = Collections.emptyList();

        // 1. MySQL (B mode) functions must prefer ifnull, ceiling, etc.
        boolean sawMySQLFunc = false;
        for (int i = 0; i < 50; i++) {
            GaussDBExpression fn = GaussDBBoundaryHelper.generateBoundaryFunction(r, GaussDBDataType.INT, emptyArgs, CompatibilityMode.B);
            String str = GaussDBToStringVisitor.asString(fn);
            if (str.contains("ifnull") || str.contains("ceiling") || str.contains("locate")) {
                sawMySQLFunc = true;
                break;
            }
        }
        assertTrue(sawMySQLFunc, "MySQL mode should generate MySQL built-in functions (ifnull, ceiling, locate)");

        // 2. PG mode functions must prefer coalesce, strpos, etc.
        boolean sawPGFunc = false;
        for (int i = 0; i < 50; i++) {
            GaussDBExpression fn = GaussDBBoundaryHelper.generateBoundaryFunction(r, GaussDBDataType.INT, emptyArgs, CompatibilityMode.PG);
            String str = GaussDBToStringVisitor.asString(fn);
            if (str.contains("coalesce") || str.contains("strpos")) {
                sawPGFunc = true;
                break;
            }
        }
        assertTrue(sawPGFunc, "PG mode should generate PG built-in functions (coalesce, strpos)");

        // 3. Oracle (A mode) functions must prefer nvl, bitand, etc.
        boolean sawOracleFunc = false;
        for (int i = 0; i < 50; i++) {
            GaussDBExpression fn = GaussDBBoundaryHelper.generateBoundaryFunction(r, GaussDBDataType.INT, emptyArgs, CompatibilityMode.A);
            String str = GaussDBToStringVisitor.asString(fn);
            if (str.contains("nvl") || str.contains("bitand")) {
                sawOracleFunc = true;
                break;
            }
        }
        assertTrue(sawOracleFunc, "Oracle mode should generate Oracle built-in functions (nvl, bitand)");
    }

    @Test
    void testPLSQLDynamicSQLDialectAcrossModes() {
        GaussDBColumn col = new GaussDBColumn("c0", GaussDBDataType.INT);
        GaussDBTable table = new GaussDBTable("t0", Collections.singletonList(col), Collections.emptyList(), false, true);

        // Oracle Mode (A): uses EXECUTE IMMEDIATE
        PLSQLBundle bundleA = GaussDBPLSQLOracle.buildDynamicSQLBundle(
                "fn_a", table, col, GaussDBConstant.createTrue(), CompatibilityMode.A);
        assertTrue(bundleA.getCreateFunctionSql().contains("EXECUTE IMMEDIATE"));

        // PostgreSQL Mode (PG): uses EXECUTE
        PLSQLBundle bundlePG = GaussDBPLSQLOracle.buildDynamicSQLBundle(
                "fn_pg", table, col, GaussDBConstant.createTrue(), CompatibilityMode.PG);
        assertTrue(bundlePG.getCreateFunctionSql().contains("    EXECUTE '"));
        assertFalse(bundlePG.getCreateFunctionSql().contains("EXECUTE IMMEDIATE"));
    }

    @Test
    void testGlobalStateCompatibilityModeSetting() {
        GaussDBGlobalState state = new GaussDBGlobalState();
        assertEquals(CompatibilityMode.A, state.getCompatibilityMode()); // default

        state.setCompatibilityMode(CompatibilityMode.B);
        assertEquals(CompatibilityMode.B, state.getCompatibilityMode());
        assertTrue(state.getCompatibilityMode().isMySQL());

        state.setCompatibilityMode(CompatibilityMode.PG);
        assertEquals(CompatibilityMode.PG, state.getCompatibilityMode());
        assertTrue(state.getCompatibilityMode().isPostgres());
    }
}
