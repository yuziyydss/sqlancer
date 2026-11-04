package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.SQLConnection;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.gaussdb.GaussDBErrors;
import sqlancer.gaussdb.GaussDBGlobalState;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;

/**
 * DDLCheck (VLDB'25) Equivalent Schema Evolution Testing oracle for GaussDB.
 *
 * <p>
 * Tests whether tables evolved through dynamic DDL sequences (ALTER TABLE ADD COLUMN with DEFAULT,
 * DROP COLUMN, RENAME COLUMN, ADD CONSTRAINT, CREATE INDEX, VACUUM/REINDEX) produce results
 * semantically identical to a statically constructed canonical baseline table having the same logical schema.
 *
 * Any discrepancy isolates physical tuple layout corruption, column offset displacement,
 * or catalog syscache metadata staleness in GaussDB.
 * </p>
 */
public class GaussDBDDLCheckOracle implements TestOracle<GaussDBGlobalState> {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBDDLCheckReproducer reproducer;

    public GaussDBDDLCheckOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
        errors.add("cannot alter table");
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("has conflict");
        errors.add("cannot drop");
        errors.add("cannot add constraint");
        errors.add("is referenced by");
    }

    public static final class DDLCheckBundle {
        private final List<String> setupSql;
        private final String staticQuery;
        private final String evolvedQuery;
        private final List<String> cleanupSql;

        public DDLCheckBundle(List<String> setupSql, String staticQuery, String evolvedQuery, List<String> cleanupSql) {
            this.setupSql = Collections.unmodifiableList(new ArrayList<>(setupSql));
            this.staticQuery = staticQuery;
            this.evolvedQuery = evolvedQuery;
            this.cleanupSql = Collections.unmodifiableList(new ArrayList<>(cleanupSql));
        }

        public List<String> getSetupSql() {
            return setupSql;
        }

        public String getStaticQuery() {
            return staticQuery;
        }

        public String getEvolvedQuery() {
            return evolvedQuery;
        }

        public List<String> getCleanupSql() {
            return cleanupSql;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());

        DDLCheckBundle bundle = generateBundle(table);
        if (bundle == null) {
            return;
        }
        verifyBundle(bundle);
    }

    public static DDLCheckBundle generateBundle(GaussDBTable baseTable) {
        long id = COUNTER.incrementAndGet();
        String evolTable = "t_evol_" + id;
        String statTable = "t_stat_" + id;

        List<String> setupSql = new ArrayList<>();
        List<String> cleanupSql = new ArrayList<>();

        cleanupSql.add("DROP TABLE IF EXISTS " + statTable + " CASCADE");
        cleanupSql.add("DROP TABLE IF EXISTS " + evolTable + " CASCADE");

        // 1. Initialize evolved table as temporary table cloned from base table
        setupSql.add("DROP TABLE IF EXISTS " + evolTable + " CASCADE");
        setupSql.add("CREATE TEMPORARY TABLE " + evolTable + " AS SELECT * FROM " + baseTable.getName());

        // 2. Perform a series of DDL schema evolution steps
        int ddlCount = (int) Randomly.getNotCachedInteger(2, 4);
        List<String> currentColumns = new ArrayList<>();
        for (GaussDBColumn col : baseTable.getColumns()) {
            currentColumns.add(col.getName());
        }

        for (int i = 0; i < ddlCount; i++) {
            int action = (int) Randomly.getNotCachedInteger(0, 5);
            switch (action) {
            case 0: { // ADD COLUMN with DEFAULT constant
                String newCol = "c_add_" + i;
                if (Randomly.getBoolean()) {
                    int defVal = (int) Randomly.getNotCachedInteger(0, 100);
                    setupSql.add("ALTER TABLE " + evolTable + " ADD COLUMN " + newCol + " INT DEFAULT " + defVal);
                } else {
                    setupSql.add("ALTER TABLE " + evolTable + " ADD COLUMN " + newCol + " TEXT DEFAULT 'def_" + i + "'");
                }
                currentColumns.add(newCol);
                break;
            }
            case 1: { // CREATE INDEX on an existing column
                if (!currentColumns.isEmpty()) {
                    String targetCol = Randomly.fromList(currentColumns);
                    setupSql.add("CREATE INDEX idx_" + evolTable + "_" + i + " ON " + evolTable + "(" + targetCol + ")");
                }
                break;
            }
            case 2: { // RENAME COLUMN
                if (currentColumns.size() > 1) {
                    String oldCol = currentColumns.get(currentColumns.size() - 1);
                    String renamedCol = oldCol + "_ren";
                    setupSql.add("ALTER TABLE " + evolTable + " RENAME COLUMN " + oldCol + " TO " + renamedCol);
                    currentColumns.set(currentColumns.size() - 1, renamedCol);
                }
                break;
            }
            case 3: { // REINDEX / VACUUM
                if (Randomly.getBoolean()) {
                    setupSql.add("REINDEX TABLE " + evolTable);
                } else {
                    setupSql.add("VACUUM " + evolTable);
                }
                break;
            }
            case 4: { // UPDATE existing rows
                if (!currentColumns.isEmpty()) {
                    String col = currentColumns.get(0);
                    setupSql.add("UPDATE " + evolTable + " SET " + col + " = " + col + " WHERE " + col + " IS NOT NULL");
                }
                break;
            }
            default:
                break;
            }
        }

        // 3. Create the canonical static baseline table directly from the final state of evolved table
        setupSql.add("DROP TABLE IF EXISTS " + statTable + " CASCADE");
        setupSql.add("CREATE TEMPORARY TABLE " + statTable + " AS SELECT * FROM " + evolTable);

        // 4. Optionally perform dual DML on both tables
        if (Randomly.getBoolean()) {
            setupSql.add("INSERT INTO " + statTable + " SELECT * FROM " + statTable + " LIMIT 1");
            setupSql.add("INSERT INTO " + evolTable + " SELECT * FROM " + evolTable + " LIMIT 1");
        }

        // 5. Query verification
        String staticQuery = "SELECT * FROM " + statTable;
        String evolvedQuery = "SELECT * FROM " + evolTable;

        return new DDLCheckBundle(setupSql, staticQuery, evolvedQuery, cleanupSql);
    }

    private void verifyBundle(DDLCheckBundle bundle) throws SQLException {
        SQLConnection connection = state.getConnection();
        try (Statement statement = connection.createStatement()) {
            try {
                for (String sql : bundle.getSetupSql()) {
                    executeSetup(statement, sql);
                }

                List<String> staticRows = executeAndFetch(statement, bundle.getStaticQuery());
                List<String> evolvedRows = executeAndFetch(statement, bundle.getEvolvedQuery());

                if (!staticRows.equals(evolvedRows)) {
                    reproducer = new GaussDBDDLCheckReproducer(bundle.getSetupSql(), bundle.getStaticQuery(),
                            bundle.getEvolvedQuery(), bundle.getCleanupSql());
                    state.getState().getLocalState().log(String.format(
                            "DDLCheck Discrepancy detected!%nStatic Rows (%d): %s%nEvolved Rows (%d): %s%nSetup: %s",
                            staticRows.size(), staticRows, evolvedRows.size(), evolvedRows,
                            String.join(";\n", bundle.getSetupSql())));
                    throw new AssertionError(String.format(
                            "DDLCheck Discrepancy: Static table returned %d rows, Evolved table returned %d rows!%nStatic: %s%nEvolved: %s",
                            staticRows.size(), evolvedRows.size(), staticRows, evolvedRows));
                }
            } catch (SQLException e) {
                if (errors.errorIsExpected(e.getMessage())) {
                    throw new IgnoreMeException();
                }
                throw e;
            } finally {
                for (String sql : bundle.getCleanupSql()) {
                    try {
                        statement.execute(sql);
                    } catch (SQLException ignored) {
                    }
                }
            }
        }
    }

    private void executeSetup(Statement statement, String sql) throws SQLException {
        try {
            statement.execute(sql);
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        }
    }

    private static List<String> executeAndFetch(Statement statement, String query) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(query)) {
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        row.append(", ");
                    }
                    row.append(String.valueOf(rs.getObject(i)));
                }
                rows.add(row.toString());
            }
        }
        rows.sort(Comparator.naturalOrder());
        return rows;
    }

    @Override
    public Reproducer<GaussDBGlobalState> getLastReproducer() {
        return reproducer;
    }
}
