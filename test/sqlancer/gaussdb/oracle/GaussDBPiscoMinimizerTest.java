package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import sqlancer.gaussdb.oracle.GaussDBPiscoMinimizer.AtomicBlock;

class GaussDBPiscoMinimizerTest {

    @BeforeEach
    void setUp() {
        GaussDBPiscoMinimizer.clearRegistry();
    }

    @Test
    void testExtractTablesFromQueries() {
        Set<String> t1 = GaussDBPiscoMinimizer.extractTables("SELECT a FROM t_users JOIN t_orders ON t_users.id = t_orders.uid;");
        assertTrue(t1.contains("t_users"));
        assertTrue(t1.contains("t_orders"));

        Set<String> t2 = GaussDBPiscoMinimizer.extractTables("INSERT INTO t_accounts VALUES (1, 100);");
        assertTrue(t2.contains("t_accounts"));

        Set<String> t3 = GaussDBPiscoMinimizer.extractTables("UPDATE t_ledger SET balance = 50 WHERE id = 1;");
        assertTrue(t3.contains("t_ledger"));
    }

    @Test
    void testParseBlocksPreservesTransactionAtomicity() {
        List<String> script = Arrays.asList(
                "CREATE TABLE t0 (id INT);",
                "BEGIN;",
                "UPDATE t0 SET id = 1;",
                "UPDATE t0 SET id = 2;",
                "COMMIT;",
                "SELECT * FROM t0;");

        List<AtomicBlock> blocks = GaussDBPiscoMinimizer.parseBlocks(script);
        assertEquals(3, blocks.size());
        assertEquals("CREATE TABLE t0 (id INT);", blocks.get(0).getStatements().get(0));
        assertEquals(4, blocks.get(1).getStatements().size()); // BEGIN, UPDATE, UPDATE, COMMIT in 1 atomic block
        assertEquals("SELECT * FROM t0;", blocks.get(2).getStatements().get(0));
    }

    @Test
    void testPruneDeadBlocksEliminatesUnrelatedTables() {
        List<String> script = Arrays.asList(
                "CREATE TABLE t_unrelated (x INT);",
                "INSERT INTO t_unrelated VALUES (999);",
                "CREATE TABLE t_target (id INT, v INT);",
                "INSERT INTO t_target VALUES (1, 100);",
                "SELECT * FROM t_target WHERE v > 50;");

        List<AtomicBlock> blocks = GaussDBPiscoMinimizer.parseBlocks(script);
        List<AtomicBlock> pruned = GaussDBPiscoMinimizer.pruneDeadBlocks(blocks);

        List<String> flattened = GaussDBPiscoMinimizer.flattenBlocks(pruned);
        assertEquals(3, flattened.size());
        assertFalse(flattened.contains("CREATE TABLE t_unrelated (x INT);"));
        assertFalse(flattened.contains("INSERT INTO t_unrelated VALUES (999);"));
        assertTrue(flattened.contains("CREATE TABLE t_target (id INT, v INT);"));
        assertTrue(flattened.contains("INSERT INTO t_target VALUES (1, 100);"));
        assertTrue(flattened.contains("SELECT * FROM t_target WHERE v > 50;"));
    }

    @Test
    void testMinimizerProducesMinimalSubsequence() {
        List<String> script = Arrays.asList(
                "SET query_dop = 2;", // Irrelevant
                "CREATE TABLE t_data (id INT, val INT);", // Required
                "INSERT INTO t_data VALUES (1, 10);", // Irrelevant
                "INSERT INTO t_data VALUES (2, 20);", // Required (triggers bug condition)
                "INSERT INTO t_data VALUES (3, 30);", // Irrelevant
                "SELECT SUM(val) FROM t_data WHERE id = 2;" // Required
        );

        // Bug condition: requires "CREATE TABLE t_data" AND "id = 2" AND "SELECT"
        List<String> minimal = GaussDBPiscoMinimizer.minimize(script, candidate -> {
            boolean hasCreate = candidate.stream().anyMatch(s -> s.contains("CREATE TABLE t_data"));
            boolean hasBugRow = candidate.stream().anyMatch(s -> s.contains("VALUES (2, 20)"));
            boolean hasQuery = candidate.stream().anyMatch(s -> s.contains("SELECT SUM(val)"));
            return hasCreate && hasBugRow && hasQuery;
        });

        assertEquals(3, minimal.size());
        assertTrue(minimal.contains("CREATE TABLE t_data (id INT, val INT);"));
        assertTrue(minimal.contains("INSERT INTO t_data VALUES (2, 20);"));
        assertTrue(minimal.contains("SELECT SUM(val) FROM t_data WHERE id = 2;"));
    }

    @Test
    void testDeterministicSignatureAndDeduplication() {
        List<String> run1 = Arrays.asList(
                "CREATE TABLE t_iso_1001 (id INT);",
                "INSERT INTO t_iso_1001 VALUES (10);",
                "SELECT * FROM t_iso_1001;");

        List<String> run2 = Arrays.asList(
                "CREATE TABLE t_iso_9999 (id INT);",
                "INSERT INTO t_iso_9999 VALUES (10);",
                "SELECT * FROM t_iso_9999;");

        String sig1 = GaussDBPiscoMinimizer.computeSignature("ISOREL_P2", run1);
        String sig2 = GaussDBPiscoMinimizer.computeSignature("ISOREL_P2", run2);

        assertNotNull(sig1);
        assertNotNull(sig2);
        // Ephemeral table names are normalized so both runs produce identical canonical signatures
        assertEquals(sig1, sig2);

        // First observation is NOT duplicate
        boolean dup1 = GaussDBPiscoMinimizer.recordAndCheckDuplicate(sig1);
        assertFalse(dup1);
        assertEquals(1, GaussDBPiscoMinimizer.getOccurrenceCount(sig1));

        // Second observation of the same canonical bug IS duplicate!
        boolean dup2 = GaussDBPiscoMinimizer.recordAndCheckDuplicate(sig2);
        assertTrue(dup2);
        assertEquals(2, GaussDBPiscoMinimizer.getOccurrenceCount(sig1));
        assertEquals(1, GaussDBPiscoMinimizer.getUniqueBugCount());
    }
}
