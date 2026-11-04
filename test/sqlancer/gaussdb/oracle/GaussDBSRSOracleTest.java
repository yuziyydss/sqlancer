package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import sqlancer.SQLConnection;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.oracle.GaussDBSRSReproducer.SRSType;

class GaussDBSRSOracleTest {

    @Test
    void reproducerDetectsInclusionViolation() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:gaussdb_srs_inclusion")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t0(id INT, v INT)");
                statement.execute("CREATE TABLE t1(id INT, v INT)");
                statement.execute("INSERT INTO t0 VALUES (1, 10), (2, 20)");
                statement.execute("INSERT INTO t1 VALUES (1, 10)");
            }
            GaussDBGlobalState state = new GaussDBGlobalState();
            state.setConnection(new SQLConnection(connection));

            // Valid: INNER JOIN {1} ⊆ LEFT JOIN {1, 2}
            String innerSql = "SELECT DISTINCT l.id FROM t0 AS l INNER JOIN t1 AS r ON (l.id = r.id)";
            String leftSql = "SELECT DISTINCT l.id FROM t0 AS l LEFT JOIN t1 AS r ON (l.id = r.id)";
            GaussDBSRSReproducer validReproducer = new GaussDBSRSReproducer(SRSType.INCLUSION, innerSql, leftSql, null);
            assertFalse(validReproducer.bugStillTriggers(state));

            // Buggy simulated: innerSql {1, 2} but leftSql {1} -> violation!
            String buggyLeftSql = "SELECT DISTINCT l.id FROM t0 AS l INNER JOIN t1 AS r ON (l.id = r.id)";
            String buggyInnerSql = "SELECT DISTINCT l.id FROM t0 AS l";
            GaussDBSRSReproducer bugReproducer = new GaussDBSRSReproducer(SRSType.INCLUSION, buggyInnerSql, buggyLeftSql, null);
            assertTrue(bugReproducer.bugStillTriggers(state));
        }
    }

    @Test
    void reproducerDetectsPartitionViolation() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:gaussdb_srs_partition")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t0(id INT)");
                statement.execute("CREATE TABLE t1(id INT)");
                statement.execute("INSERT INTO t0 VALUES (1), (2), (3)");
                statement.execute("INSERT INTO t1 VALUES (1), (2)");
            }
            GaussDBGlobalState state = new GaussDBGlobalState();
            state.setConnection(new SQLConnection(connection));

            String semiSql = "SELECT DISTINCT l.id FROM t0 AS l WHERE EXISTS (SELECT 1 FROM t1 AS r WHERE l.id = r.id)";
            String antiSql = "SELECT DISTINCT l.id FROM t0 AS l WHERE NOT EXISTS (SELECT 1 FROM t1 AS r WHERE l.id = r.id)";
            String baseSql = "SELECT DISTINCT l.id FROM t0 AS l";

            GaussDBSRSReproducer validReproducer = new GaussDBSRSReproducer(SRSType.SEMI_ANTI_PARTITION, semiSql, antiSql, baseSql);
            assertFalse(validReproducer.bugStillTriggers(state));

            // Incomplete base (missing row 3)
            String incompleteBase = "SELECT DISTINCT l.id FROM t0 AS l WHERE l.id < 3";
            GaussDBSRSReproducer bugReproducer = new GaussDBSRSReproducer(SRSType.SEMI_ANTI_PARTITION, semiSql, antiSql, incompleteBase);
            assertTrue(bugReproducer.bugStillTriggers(state));
        }
    }
}
