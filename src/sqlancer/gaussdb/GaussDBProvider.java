package sqlancer.gaussdb;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import com.google.auto.service.AutoService;

import sqlancer.AbstractAction;
import sqlancer.DatabaseProvider;
import sqlancer.IgnoreMeException;
import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.SQLProviderAdapter;
import sqlancer.StatementExecutor;
import sqlancer.common.DBMSCommon;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLQueryProvider;
import sqlancer.gaussdb.gen.GaussDBDeleteGenerator;
import sqlancer.gaussdb.gen.GaussDBIndexGenerator;
import sqlancer.gaussdb.gen.GaussDBInsertGenerator;
import sqlancer.gaussdb.gen.GaussDBInsertSelectGenerator;
import sqlancer.gaussdb.gen.GaussDBSetGenerator;
import sqlancer.gaussdb.gen.GaussDBTableGenerator;
import sqlancer.gaussdb.gen.GaussDBUpdateGenerator;
import sqlancer.gaussdb.gen.GaussDBViewGenerator;

@AutoService(DatabaseProvider.class)
public class GaussDBProvider extends SQLProviderAdapter<GaussDBGlobalState, GaussDBOptions> {

    private String username;
    private String password;
    private String host;
    private int port;
    private String connectionURL;
    private String testURL;
    private String schemaName;

    public GaussDBProvider() {
        super(GaussDBGlobalState.class, GaussDBOptions.class);
    }

    public enum Action implements AbstractAction<GaussDBGlobalState> {
        INSERT(GaussDBInsertGenerator::insert),
        INSERT_SELECT(GaussDBInsertSelectGenerator::insertSelect),
        UPDATE(GaussDBUpdateGenerator::update),
        DELETE(GaussDBDeleteGenerator::delete),
        CREATE_INDEX(GaussDBIndexGenerator::generate),
        CREATE_VIEW(GaussDBViewGenerator::create),
        SET(GaussDBSetGenerator::set);

        private final SQLQueryProvider<GaussDBGlobalState> sqlQueryProvider;

        Action(SQLQueryProvider<GaussDBGlobalState> sqlQueryProvider) {
            this.sqlQueryProvider = sqlQueryProvider;
        }

        @Override
        public SQLQueryAdapter getQuery(GaussDBGlobalState state) throws Exception {
            return sqlQueryProvider.getQuery(state);
        }
    }

    private static int mapActions(GaussDBGlobalState globalState, Action a) {
        Randomly r = globalState.getRandomly();
        switch (a) {
        case INSERT:
            return r.getInteger(0, globalState.getOptions().getMaxNumberInserts());
        case INSERT_SELECT:
            return r.getInteger(0, 2);
        case UPDATE:
            return r.getInteger(0, 2);
        case DELETE:
            return r.getInteger(0, 2);
        case CREATE_INDEX:
            return r.getInteger(0, 2);
        case CREATE_VIEW:
            return r.getInteger(0, 2);
        case SET:
            return r.getInteger(0, 3);
        default:
            throw new AssertionError(a);
        }
    }

    @Override
    public void generateDatabase(GaussDBGlobalState globalState) throws Exception {
        createTables(globalState, Randomly.fromOptions(1, 2, 3));
        prepareTables(globalState);
    }

    @Override
    public SQLConnection createDatabase(GaussDBGlobalState globalState) throws SQLException {
        username = globalState.getOptions().getUserName();
        password = globalState.getOptions().getPassword();
        host = globalState.getOptions().getHost();
        port = globalState.getOptions().getPort();
        connectionURL = globalState.getDbmsSpecificOptions().connectionURL;
        schemaName = resolveSchemaSlotName(globalState.getDatabaseName());

        if (connectionURL.startsWith("jdbc:")) {
            connectionURL = connectionURL.substring(5);
        }

        try {
            URI uri = new URI(connectionURL);
            if (uri.getUserInfo() != null) {
                String[] userInfo = uri.getUserInfo().split(":", 2);
                username = userInfo[0];
                if (userInfo.length > 1) {
                    password = userInfo[1];
                }
            }
            if (host == null) {
                host = uri.getHost();
            }
            if (port == MainOptions.NO_SET_PORT) {
                port = uri.getPort();
            }
            testURL = String.format("%s://%s:%d%s", uri.getScheme(), host, port,
                    uri.getPath() == null ? "/postgres" : uri.getPath());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }

        Connection con = DriverManager.getConnection("jdbc:" + testURL, username, password);
        try (Statement s = con.createStatement()) {
            if (globalState.getDbmsSpecificOptions().useSchemaIsolation) {
                s.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
                s.execute("CREATE SCHEMA " + schemaName);
                s.execute("SET search_path TO " + schemaName);
            }
            for (String sessionGuc : Arrays.asList("SET disable_gpc_store = on", "SET cursor_sharing = off",
                    "SET spm_enable_plan_capture = off", "SET spm_enable_plan_history_logging = off",
                    "SET instr_unique_sql_track_type = top", "SELECT reset_unique_sql('GLOBAL', 'ALL', 0)",
                    "SELECT plancache_clean()")) {
                try {
                    s.execute(sessionGuc);
                } catch (SQLException ignored) {
                    // Best-effort session GUCs to prevent global plan/SPM/unique-SQL cache bloat during high-speed fuzzing.
                }
            }
        }
        return new SQLConnection(con);
    }

    @Override
    public String getDBMSName() {
        return "gaussdb";
    }

    // Assigns each worker thread a dedicated schema slot so concurrent threads never collide and each thread's
    // previous tables/partitions/indexes are dropped automatically when it rotates to the next database.
    private static String resolveSchemaSlotName(String databaseName) {
        if (databaseName != null && databaseName.endsWith("connectiontest")) {
            return "sqlancer_conntest";
        }
        return "sqlancer_worker_" + Thread.currentThread().getId();
    }

    protected void createTables(GaussDBGlobalState globalState, int numTables) throws Exception {
        while (globalState.getSchema().getDatabaseTables().size() < numTables) {
            try {
                String tableName = DBMSCommon.createTableName(globalState.getSchema().getDatabaseTables().size());
                globalState.executeStatement(GaussDBTableGenerator.generate(tableName, globalState));
            } catch (IgnoreMeException e) {
                // Retry table creation.
            }
        }
    }

    protected void prepareTables(GaussDBGlobalState globalState) throws Exception {
        StatementExecutor<GaussDBGlobalState, Action> se = new StatementExecutor<>(globalState, Action.values(),
                GaussDBProvider::mapActions, (q) -> {
                    if (globalState.getSchema().getDatabaseTables().isEmpty()) {
                        throw new IgnoreMeException();
                    }
                });
        se.executeStatements();
        globalState.executeStatement(new SQLQueryAdapter("COMMIT", true));
    }

}
