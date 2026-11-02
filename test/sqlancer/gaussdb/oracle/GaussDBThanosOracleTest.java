package sqlancer.gaussdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;
import sqlancer.gaussdb.oracle.GaussDBThanosOracle.EngineQueryPair;

class GaussDBThanosOracleTest {

    private final GaussDBColumn intCol = new GaussDBColumn("c0", GaussDBDataType.INT);
    private final GaussDBTable table = new GaussDBTable("t0", List.of(intCol), null, false, true);

    @Test
    void twinTableAppendsStorageEngineSuffixAndPreservesColumns() {
        GaussDBTable astoreTwin = GaussDBThanosOracle.twinTable(table, GaussDBThanosOracle.ASTORE_SUFFIX);
        GaussDBTable ustoreTwin = GaussDBThanosOracle.twinTable(table, GaussDBThanosOracle.USTORE_SUFFIX);

        assertEquals("t0_thanos_astore", astoreTwin.getName());
        assertEquals("t0_thanos_ustore", ustoreTwin.getName());
        assertEquals(1, astoreTwin.getColumns().size());
        assertEquals("c0", astoreTwin.getColumns().get(0).getName());
    }

    @Test
    void createSingleTablePairBuildsAstoreAndUstoreCreateTableAsSelectStatements() {
        GaussDBGlobalState state = new GaussDBGlobalState();
        state.setRandomly(new Randomly(1L));
        GaussDBThanosOracle oracle = new GaussDBThanosOracle(state);

        EngineQueryPair pair = oracle.createSingleTablePair(table,
                "SELECT * FROM t0_thanos_astore AS t WHERE TRUE",
                "SELECT * FROM t0_thanos_ustore AS t WHERE TRUE");

        assertTrue(pair.getSetupSql().contains("DROP TABLE IF EXISTS t0_thanos_astore, t0_thanos_ustore CASCADE"));
        assertTrue(pair.getSetupSql()
                .contains("CREATE TABLE t0_thanos_astore WITH (storage_type = astore) AS SELECT * FROM t0"));
        assertTrue(pair.getSetupSql()
                .contains("CREATE TABLE t0_thanos_ustore WITH (storage_type = ustore) AS SELECT * FROM t0"));
        assertEquals(List.of("DROP TABLE IF EXISTS t0_thanos_astore, t0_thanos_ustore CASCADE"), pair.getCleanupSql());
    }

    @Test
    void reproducerDetectsDiscrepancyBetweenAstoreAndUstoreQueries() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:gaussdb_thanos_reproducer")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t0_a(c0 INT)");
                statement.execute("CREATE TABLE t0_u(c0 INT)");
                statement.execute("INSERT INTO t0_a VALUES (1), (2)");
                statement.execute("INSERT INTO t0_u VALUES (1), (3)");
            }
            GaussDBGlobalState state = new GaussDBGlobalState();
            state.setConnection(new SQLConnection(connection));

            EngineQueryPair mismatchPair = new EngineQueryPair(List.of(), "SELECT c0 FROM t0_a", "SELECT c0 FROM t0_u",
                    List.of());
            assertTrue(new GaussDBThanosReproducer(mismatchPair).bugStillTriggers(state));

            EngineQueryPair matchingPair = new EngineQueryPair(List.of(), "SELECT c0 FROM t0_a", "SELECT c0 FROM t0_a",
                    List.of());
            assertFalse(new GaussDBThanosReproducer(matchingPair).bugStillTriggers(state));
        }
    }

}
