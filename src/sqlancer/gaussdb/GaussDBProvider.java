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

        Connection con = null;
        int maxAttempts = 30; // Retry up to 5 minutes to gracefully survive database OOM-killer restarts
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                con = DriverManager.getConnection("jdbc:" + testURL, username, password);
                try (Statement s = con.createStatement()) {
                    try {
                        s.execute("DISCARD ALL");
                    } catch (SQLException ignored) {
                    }
                    if (globalState.getDbmsSpecificOptions().useSchemaIsolation) {
                        s.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
                        s.execute("CREATE SCHEMA " + schemaName);
                        s.execute("SET search_path TO " + schemaName);
                    }
                    // Detect or enforce SQL compatibility mode (A: Oracle, B: MySQL/M, PG: PostgreSQL)
                    GaussDBOptions.CompatibilityMode mode = globalState.getDbmsSpecificOptions().compatibilityMode;
                    if (mode == GaussDBOptions.CompatibilityMode.AUTO) {
                        try (java.sql.ResultSet rs = s.executeQuery("SHOW sql_compatibility")) {
                            if (rs.next()) {
                                String val = rs.getString(1).trim().toUpperCase(java.util.Locale.ROOT);
                                if (val.contains("B") || val.contains("MYSQL") || val.contains("M")) {
                                    mode = GaussDBOptions.CompatibilityMode.B;
                                } else if (val.contains("PG") || val.contains("POSTGRES")) {
                                    mode = GaussDBOptions.CompatibilityMode.PG;
                                } else {
                                    mode = GaussDBOptions.CompatibilityMode.A;
                                }
                            }
                        } catch (SQLException ignored) {
                            mode = GaussDBOptions.CompatibilityMode.A;
                        }
                    }
                    globalState.setCompatibilityMode(mode);
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
                break;
            } catch (SQLException e) {
                if (con != null) {
                    try {
                        con.close();
                    } catch (SQLException ignored) {
                    }
                    con = null;
                }
                if (attempt < maxAttempts && isTransientConnectionError(e)) {
                    System.err.printf("[GaussDBProvider] Database connection attempt %d/%d failed (%s). Waiting 10s for database restart/recovery...%n",
                            attempt, maxAttempts, e.getMessage());
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                throw e;
            }
        }
        return new SQLConnection(con);
    }

    public static boolean isTransientConnectionError(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("connection refused")
                        || (lower.contains("connection to") && lower.contains("refused"))
                        || lower.contains("starting up")
                        || lower.contains("recovery mode")
                        || lower.contains("shutting down")
                        || lower.contains("connection reset")
                        || lower.contains("broken pipe")
                        || lower.contains("closed connection")
                        || lower.contains("terminating connection")
                        || lower.contains("socket closed")
                        || lower.contains("sockettimeout")
                        || lower.contains("eofexception")
                        || lower.contains("i/o error")
                        || lower.contains("memory is temporarily unavailable")
                        || lower.contains("too many clients")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
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
        int attempts = 0;
        while (globalState.getSchema().getDatabaseTables().size() < numTables) {
            attempts++;
            if (attempts > 50) {
                throw new IgnoreMeException();
            }
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
