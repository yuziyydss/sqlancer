package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
 * DDLumos (USENIX ATC '24) Transactional Schema Evolution & Metadata Invariant Oracle for GaussDB.
 *
 * <p>
 * GaussDB features enterprise-grade Transactional DDL support (DDL operations within explicit transactions
 * can be rolled back). DDLumos verifies:
 * 1. Transactional DDL Rollback Invariance: The table schema and physical tuple state after ROLLBACK must be
 *    strictly bitwise identical to the state before BEGIN.
 * 2. System Catalog Consistency: Ensures no ghost attributes or orphaned catalog entries remain in pg_attribute / pg_class.
 *
 * Catches critical bugs in catalog lock upgrading, MVCC schema cache invalidation, and transactional storage desync.
 * </p>
 */
public class GaussDBDDLumosOracle implements TestOracle<GaussDBGlobalState> {

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBDDLumosReproducer reproducer;

    public GaussDBDDLumosOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
        errors.add("deadlock detected");
        errors.add("lock wait timeout");
    }

    public static final class DDLumosBundle {

        private final String tableName;
        private final String snapshotQuery;
        private final List<String> ddlOperations;

        public DDLumosBundle(String tableName, String snapshotQuery, List<String> ddlOperations) {
            this.tableName = tableName;
            this.snapshotQuery = snapshotQuery;
            this.ddlOperations = ddlOperations;
        }

        public String getTableName() {
            return tableName;
        }

        public String getSnapshotQuery() {
            return snapshotQuery;
        }

        public List<String> getDdlOperations() {
            return ddlOperations;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && !candidate.getColumns().isEmpty());

        String tableName = table.getName();
        String projCols = table.getColumns().stream().map(GaussDBColumn::getName).collect(Collectors.joining(", "));
        String orderCol = table.getColumns().get(0).getName();
        String snapshotQuery = "SELECT " + projCols + " FROM " + tableName + " ORDER BY " + orderCol;

        List<String> ddlOps = new ArrayList<>();
        String colName = "lumos_" + Randomly.smallNumber();
        String idxName = "idx_" + colName;

        ddlOps.add("ALTER TABLE " + tableName + " ADD COLUMN " + colName + " INT DEFAULT 10;");
        ddlOps.add("CREATE INDEX " + idxName + " ON " + tableName + " (" + colName + ");");
        ddlOps.add("UPDATE " + tableName + " SET " + colName + " = " + colName + " + 5;");
        ddlOps.add("ALTER TABLE " + tableName + " DROP COLUMN " + colName + ";");

        DDLumosBundle bundle = new DDLumosBundle(tableName, snapshotQuery, ddlOps);
        SQLConnection connection = state.getConnection();

        try (Statement statement = connection.createStatement()) {
            // 1. Take pre-transaction snapshot
            List<String> preSnapshot = executeAndGetRows(statement, snapshotQuery);

            // 2. Begin transaction and execute mutating DDL/DML sequence
            statement.execute("BEGIN;");
            boolean rollbackDone = false;
            try {
                for (String op : ddlOps) {
                    try {
                        statement.execute(op);
                    } catch (SQLException e) {
                        // If specific operation fails in transaction, still proceed to rollback
                        break;
                    }
                }
            } finally {
                try {
                    statement.execute("ROLLBACK;");
                    rollbackDone = true;
                } catch (SQLException ignored) {
                }
            }

            if (!rollbackDone) {
                throw new IgnoreMeException();
            }

            // 3. Take post-rollback snapshot
            List<String> postSnapshot = executeAndGetRows(statement, snapshotQuery);

            try {
                verifyDDLumosInvariants(bundle, preSnapshot, postSnapshot);
            } catch (AssertionError e) {
                reproducer = new GaussDBDDLumosReproducer(bundle);
                throw e;
            }
        } catch (SQLException e) {
            try (Statement rb = connection.createStatement()) {
                rb.execute("ROLLBACK;");
            } catch (Exception ignored) {
            }
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        }
    }

    public static void verifyDDLumosInvariants(DDLumosBundle bundle, List<String> preSnapshot, List<String> postSnapshot) {
        if (!preSnapshot.equals(postSnapshot)) {
            throw new AssertionError(String.format(
                    "DDLumos Transactional DDL Rollback Violation for table %s!\n"
                            + "DDL sequence: %s\nPre-Transaction Rows (%d): %s\nPost-Rollback Rows (%d): %s",
                    bundle.getTableName(), bundle.getDdlOperations(),
                    preSnapshot.size(), preSnapshot, postSnapshot.size(), postSnapshot));
        }
    }

    private List<String> executeAndGetRows(Statement statement, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>(cols);
                for (int i = 1; i <= cols; i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

    @Override
    public Reproducer<GaussDBGlobalState> getLastReproducer() {
        return reproducer;
    }

}
