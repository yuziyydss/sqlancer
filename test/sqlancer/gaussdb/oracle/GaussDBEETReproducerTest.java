package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import sqlancer.SQLConnection;
import sqlancer.gaussdb.GaussDBGlobalState;

class GaussDBEETReproducerTest {

    @Test
    void bugStillTriggersWhenResultsDiffer() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:gaussdb_eet_reproducer")) {
            GaussDBGlobalState state = new GaussDBGlobalState();
            state.setConnection(new SQLConnection(connection));

            GaussDBEETReproducer reproducer = new GaussDBEETReproducer("SELECT 1", "SELECT 2");
            assertTrue(reproducer.bugStillTriggers(state));
        }
    }

    @Test
    void bugDoesNotTriggerWhenNullValuesMatch() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:gaussdb_eet_reproducer_null")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t0(c0 INT)");
                statement.execute("INSERT INTO t0 VALUES (NULL), (1), (NULL)");
            }

            GaussDBGlobalState state = new GaussDBGlobalState();
            state.setConnection(new SQLConnection(connection));

            GaussDBEETReproducer reproducer = new GaussDBEETReproducer("SELECT c0 FROM t0", "SELECT c0 FROM t0");
            assertFalse(reproducer.bugStillTriggers(state));
        }
    }

    @Test
    void bugStillTriggersWhenOnlySecondColumnDiffers() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:gaussdb_eet_reproducer_rows")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t0(c0 INT, c1 INT)");
                statement.execute("INSERT INTO t0 VALUES (1, 1)");
            }

            GaussDBGlobalState state = new GaussDBGlobalState();
            state.setConnection(new SQLConnection(connection));

            GaussDBEETReproducer reproducer = new GaussDBEETReproducer("SELECT c0, c1 FROM t0",
                    "SELECT c0, c1 + 1 FROM t0");
            assertTrue(reproducer.bugStillTriggers(state));
        }
    }

    @Test
    void orderedReproducerDetectsRowOrderMismatchWithSameMultiset() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:gaussdb_eet_reproducer_ordered")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t0(c0 INT)");
                statement.execute("INSERT INTO t0 VALUES (1), (2)");
            }

            GaussDBGlobalState state = new GaussDBGlobalState();
            state.setConnection(new SQLConnection(connection));

            GaussDBEETReproducer unordered = new GaussDBEETReproducer("SELECT c0 FROM t0 ORDER BY c0 ASC",
                    "SELECT c0 FROM t0 ORDER BY c0 DESC", false);
            assertFalse(unordered.bugStillTriggers(state));

            GaussDBEETReproducer ordered = new GaussDBEETReproducer("SELECT c0 FROM t0 ORDER BY c0 ASC",
                    "SELECT c0 FROM t0 ORDER BY c0 DESC", true);
            assertTrue(ordered.bugStillTriggers(state));
            assertTrue(ordered.isOrdered());
        }
    }

}
