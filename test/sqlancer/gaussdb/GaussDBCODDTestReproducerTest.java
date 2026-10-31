package sqlancer.gaussdb;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.jupiter.api.Test;

import sqlancer.SQLConnection;
import sqlancer.gaussdb.oracle.GaussDBCODDTestReproducer;

class GaussDBCODDTestReproducerTest {

    @Test
    void bugStillTriggersWhenResultsDiffer() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:gaussdb_reproducer")) {
            GaussDBGlobalState state = new GaussDBGlobalState();
            state.setConnection(new SQLConnection(connection));

            GaussDBCODDTestReproducer reproducer = new GaussDBCODDTestReproducer("SELECT 1", "SELECT 1", "SELECT 2");
            assertTrue(reproducer.bugStillTriggers(state));
        }
    }

    @Test
    void bugDoesNotTriggerWhenResultsMatch() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:gaussdb_reproducer_match")) {
            GaussDBGlobalState state = new GaussDBGlobalState();
            state.setConnection(new SQLConnection(connection));

            GaussDBCODDTestReproducer reproducer = new GaussDBCODDTestReproducer("SELECT 1", "SELECT 1", "SELECT 1");
            assertFalse(reproducer.bugStillTriggers(state));
        }
    }

}
