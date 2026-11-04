package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.SQLConnection;
import sqlancer.gaussdb.GaussDBGlobalState;

class GaussDBARGOracleTest {

    @Test
    void allRewriteRulesContainsCoreOptimizerRules() {
        assertTrue(GaussDBARGOracle.ALL_REWRITE_RULES.contains("cse_rewrite_opt"));
        assertTrue(GaussDBARGOracle.ALL_REWRITE_RULES.contains("uniquecheck"));
        assertTrue(GaussDBARGOracle.ALL_REWRITE_RULES.contains("magicset"));
        assertTrue(GaussDBARGOracle.ALL_REWRITE_RULES.contains("partialpush"));
        assertTrue(GaussDBARGOracle.ALL_REWRITE_RULES.contains("groupby_pushdown_subquery"));
        assertTrue(GaussDBARGOracle.ALL_REWRITE_RULES.contains("enable_corr_any_sublink_semijoin_to_innerjoin"));
        assertTrue(GaussDBARGOracle.ALL_REWRITE_RULES.contains("enable_sublink_pullup_enhanced"));
    }

    @Test
    void reproducerHandlesEquivalentResultsGracefully() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:gaussdb_arg_test")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t0(id INT, v INT)");
                statement.execute("INSERT INTO t0 VALUES (1, 10), (2, 20)");
            }
            GaussDBGlobalState state = new GaussDBGlobalState();
            state.setConnection(new SQLConnection(connection));

            String query = "SELECT DISTINCT id, v FROM t0 WHERE id > 0";
            GaussDBARGReproducer reproducer = new GaussDBARGReproducer(query, "uniquecheck", List.of("uniquecheck"));
            // In H2, SET rewrite_rule will be ignored or mocked, queries return same result -> bug does not trigger
            assertFalse(reproducer.bugStillTriggers(state));
        }
    }
}
