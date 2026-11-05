package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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

/**
 * Accio (CCS '26) & Beacon (OOPSLA '26) DBMS Privilege Escalation, RBAC & Row-Level Security (RLS) Oracle for GaussDB.
 *
 * <p>
 * Tests enterprise-grade access control enforcement, multi-tenant role boundaries, and row-level filtering:
 * 1. RBAC Table & Column Access Control: A restricted role denied permissions on tables/columns MUST be
 *    strictly blocked. Any query execution succeeding in reading denied tables or columns is flagged as
 *    a Critical CVE-level Privilege Escalation defect.
 * 2. Row-Level Security (RLS) Invariance: Under active RLS policies (CREATE ROW LEVEL SECURITY POLICY),
 *    queries executed by restricted roles must strictly satisfy the policy predicate (e.g. sec_level = 'PUBLIC').
 *    Any query plan, subquery pull-up, or index scan that leaks forbidden rows violates RLS data isolation.
 * </p>
 */
public class GaussDBAccioOracle implements TestOracle<GaussDBGlobalState> {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBAccioReproducer reproducer;

    public GaussDBAccioOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
        // Authorization & environment errors when running in unprivileged test accounts
        errors.add("permission denied to create role");
        errors.add("must be system admin");
        errors.add("must be initial user");
        errors.add("permission denied to create policy");
        errors.add("row level security is not supported");
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("cannot set role");
    }

    public enum AccioMode {
        RBAC_TABLE_ACCESS_CONTROL,
        RBAC_COLUMN_ACCESS_CONTROL,
        ROW_LEVEL_SECURITY_ENFORCEMENT
    }

    public static final class AccioBundle {

        private final AccioMode mode;
        private final String roleName;
        private final List<String> setupStatements;
        private final String testQuery;
        private final String cleanupSql;
        private final String expectedErrorSubstr;
        private final String allowedRLSLabel;

        public AccioBundle(AccioMode mode, String roleName, List<String> setupStatements,
                String testQuery, String cleanupSql, String expectedErrorSubstr, String allowedRLSLabel) {
            this.mode = mode;
            this.roleName = roleName;
            this.setupStatements = setupStatements;
            this.testQuery = testQuery;
            this.cleanupSql = cleanupSql;
            this.expectedErrorSubstr = expectedErrorSubstr;
            this.allowedRLSLabel = allowedRLSLabel;
        }

        public AccioMode getMode() {
            return mode;
        }

        public String getRoleName() {
            return roleName;
        }

        public List<String> getSetupStatements() {
            return setupStatements;
        }

        public String getTestQuery() {
            return testQuery;
        }

        public String getCleanupSql() {
            return cleanupSql;
        }

        public String getExpectedErrorSubstr() {
            return expectedErrorSubstr;
        }

        public String getAllowedRLSLabel() {
            return allowedRLSLabel;
        }
    }

    @Override
    public void check() throws SQLException {
        long runId = COUNTER.incrementAndGet();
        String roleName = "accio_role_" + runId;
        String tableName = "accio_tab_" + runId;

        AccioMode mode = Randomly.fromOptions(AccioMode.values());
        AccioBundle bundle = generateBundle(mode, roleName, tableName);

        SQLConnection connection = state.getConnection();
        executeBundle(connection, bundle);
    }

    private AccioBundle generateBundle(AccioMode mode, String roleName, String tableName) {
        List<String> setup = new ArrayList<>();
        String testQuery;
        String cleanupSql;
        String expectedError = "permission denied";
        String allowedLabel = null;

        // Cleanup script always resets role and cleans up role & table
        cleanupSql = String.format("RESET ROLE; DROP TABLE IF EXISTS %s CASCADE; DROP ROLE IF EXISTS %s;", tableName, roleName);

        switch (mode) {
        case RBAC_TABLE_ACCESS_CONTROL:
            setup.add(String.format("DROP TABLE IF EXISTS %s CASCADE;", tableName));
            setup.add(String.format("CREATE TABLE %s (id INT, info VARCHAR(64));", tableName));
            setup.add(String.format("INSERT INTO %s VALUES (1, 'sensitive_data_1'), (2, 'sensitive_data_2');", tableName));
            setup.add(String.format("DROP ROLE IF EXISTS %s;", roleName));
            setup.add(String.format("CREATE ROLE %s PASSWORD 'Accio_pass_123!';", roleName));
            setup.add(String.format("REVOKE ALL ON %s FROM %s;", tableName, roleName));
            setup.add(String.format("SET ROLE %s;", roleName));

            testQuery = String.format("SELECT * FROM %s;", tableName);
            break;

        case RBAC_COLUMN_ACCESS_CONTROL:
            setup.add(String.format("DROP TABLE IF EXISTS %s CASCADE;", tableName));
            setup.add(String.format("CREATE TABLE %s (id INT, public_col VARCHAR(64), secret_col VARCHAR(64));", tableName));
            setup.add(String.format("INSERT INTO %s VALUES (1, 'public_ok', 'cve_super_secret_token');", tableName));
            setup.add(String.format("DROP ROLE IF EXISTS %s;", roleName));
            setup.add(String.format("CREATE ROLE %s PASSWORD 'Accio_pass_123!';", roleName));
            setup.add(String.format("REVOKE ALL ON %s FROM %s;", tableName, roleName));
            setup.add(String.format("GRANT SELECT (public_col) ON %s TO %s;", tableName, roleName));
            setup.add(String.format("SET ROLE %s;", roleName));

            // Attempt to query the denied column directly or via expression
            testQuery = String.format("SELECT secret_col FROM %s;", tableName);
            break;

        case ROW_LEVEL_SECURITY_ENFORCEMENT:
        default:
            allowedLabel = "PUBLIC";
            String polName = "pol_" + tableName;
            setup.add(String.format("DROP TABLE IF EXISTS %s CASCADE;", tableName));
            setup.add(String.format("CREATE TABLE %s (id INT, sec_level VARCHAR(32), data_content VARCHAR(64));", tableName));
            setup.add(String.format("INSERT INTO %s VALUES (1, 'PUBLIC', 'allowed_1'), (2, 'CONFIDENTIAL', 'leak_1'), (3, 'PUBLIC', 'allowed_2'), (4, 'RESTRICTED', 'leak_2');", tableName));
            setup.add(String.format("ALTER TABLE %s ENABLE ROW LEVEL SECURITY;", tableName));
            setup.add(String.format("DROP ROLE IF EXISTS %s;", roleName));
            setup.add(String.format("CREATE ROLE %s PASSWORD 'Accio_pass_123!';", roleName));
            setup.add(String.format("GRANT SELECT ON %s TO %s;", tableName, roleName));
            setup.add(String.format("CREATE ROW LEVEL SECURITY POLICY %s ON %s FOR SELECT TO %s USING (sec_level = '%s');",
                    polName, tableName, roleName, allowedLabel));
            setup.add(String.format("SET ROLE %s;", roleName));

            testQuery = String.format("SELECT sec_level, data_content FROM %s;", tableName);
            expectedError = null; // Should succeed with filtered rows
            break;
        }

        return new AccioBundle(mode, roleName, setup, testQuery, cleanupSql, expectedError, allowedLabel);
    }

    private void executeBundle(SQLConnection connection, AccioBundle bundle) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : bundle.getSetupStatements()) {
                statement.execute(sql);
            }

            if (bundle.getMode() == AccioMode.ROW_LEVEL_SECURITY_ENFORCEMENT) {
                // Must return only PUBLIC rows
                List<String> returnedLabels = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery(bundle.getTestQuery())) {
                    while (rs.next()) {
                        returnedLabels.add(rs.getString(1));
                    }
                }
                try {
                    verifyRLSLeakage(returnedLabels, bundle.getAllowedRLSLabel());
                } catch (AssertionError e) {
                    reproducer = new GaussDBAccioReproducer(bundle);
                    throw e;
                }
            } else {
                // Denied resource test: query MUST throw permission denied error
                boolean accessSucceeded = false;
                try (ResultSet rs = statement.executeQuery(bundle.getTestQuery())) {
                    accessSucceeded = rs.next() || true;
                } catch (SQLException e) {
                    String msg = e.getMessage().toLowerCase();
                    if (msg.contains("permission denied") || msg.contains("denied")) {
                        // Expected security restriction properly enforced
                        accessSucceeded = false;
                    } else {
                        throw e;
                    }
                }

                try {
                    verifyNegativeAccessBlocked(accessSucceeded, bundle.getRoleName(), bundle.getTestQuery());
                } catch (AssertionError e) {
                    reproducer = new GaussDBAccioReproducer(bundle);
                    throw e;
                }
            }
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        } finally {
            try (Statement cleanStmt = connection.createStatement()) {
                cleanStmt.execute(bundle.getCleanupSql());
            } catch (Exception ignored) {
            }
        }
    }

    public static void verifyNegativeAccessBlocked(boolean accessSucceeded, String role, String resource) {
        if (accessSucceeded) {
            throw new AssertionError(String.format(
                    "Critical Security CVE (Accio / Beacon Access Control Bypass)!\n"
                            + "Role '%s' successfully accessed denied resource: %s\n"
                            + "GaussDB authorization / RBAC privilege enforcement failed!",
                    role, resource));
        }
    }

    public static void verifyRLSLeakage(List<String> returnedLabels, String allowedLabel) {
        for (String label : returnedLabels) {
            if (label == null || !allowedLabel.equalsIgnoreCase(label.trim())) {
                throw new AssertionError(String.format(
                        "Critical Security CVE (Accio / Beacon Row-Level Security Leakage)!\n"
                                + "Disallowed row with label '%s' leaked through RLS policy!\n"
                                + "Expected all rows to match policy condition '%s', but got rows: %s",
                        label, allowedLabel, returnedLabels));
            }
        }
    }

    @Override
    public Reproducer<GaussDBGlobalState> getLastReproducer() {
        return reproducer;
    }

}
