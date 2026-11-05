package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * Join Implication Reasoning (JIR, SIGMOD '26) Oracle for GaussDB.
 *
 * <p>
 * Evaluates relational join implication and equivalence laws under cost-based query optimization:
 * 1. NULL_REJECTING_OUTER_TO_INNER: A LEFT JOIN with a null-rejecting predicate on the right table simplifies
 *    to an INNER JOIN.
 * 2. INNER_INCLUSION_PROJECTION: The distinct projection of left table keys in an INNER JOIN is a subset of
 *    that in a LEFT JOIN.
 * 3. TRANSITIVE_EQUIJOIN_IMPLICATION: Join transitivity (A.c = B.c AND B.c = C.c &rArr; A.c = C.c).
 * 4. SELF_JOIN_IDENTITY: A self-join on a non-null key matches the distinct base table projection.
 *
 * Exposes bugs in join reordering, join elimination, hash/merge join pushdown, and nullability inference.
 * </p>
 */
public class GaussDBJIROracle implements TestOracle<GaussDBGlobalState> {

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBJIRReproducer reproducer;

    public GaussDBJIROracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public enum JIRMode {
        NULL_REJECTING_OUTER_TO_INNER,
        INNER_INCLUSION_PROJECTION,
        TRANSITIVE_EQUIJOIN_IMPLICATION,
        SELF_JOIN_IDENTITY
    }

    public static final class JIRBundle {

        private final JIRMode mode;
        private final String query1;
        private final String query2;

        public JIRBundle(JIRMode mode, String query1, String query2) {
            this.mode = mode;
            this.query1 = query1;
            this.query2 = query2;
        }

        public JIRMode getMode() {
            return mode;
        }

        public String getQuery1() {
            return query1;
        }

        public String getQuery2() {
            return query2;
        }
    }

    @Override
    public void check() throws SQLException {
        reproducer = null;
        List<GaussDBTable> allTables = state.getSchema().getDatabaseTables().stream()
                .filter(t -> !t.isView() && !t.getColumns().isEmpty())
                .collect(Collectors.toList());

        if (allTables.isEmpty()) {
            throw new IgnoreMeException();
        }

        GaussDBTable tableA = Randomly.fromList(allTables);
        GaussDBTable tableB = Randomly.fromList(allTables);

        JIRMode mode = Randomly.fromOptions(JIRMode.values());
        JIRBundle bundle = null;

        GaussDBColumn colA = Randomly.fromList(tableA.getColumns());
        GaussDBColumn colB = Randomly.fromList(tableB.getColumns());

        switch (mode) {
        case NULL_REJECTING_OUTER_TO_INNER: {
            String onCond = "a." + colA.getName() + " = b." + colB.getName();
            String nullRejectCond = "b." + colB.getName() + " IS NOT NULL";
            String q1 = "SELECT DISTINCT a." + colA.getName() + ", b." + colB.getName() + " FROM " + tableA.getName()
                    + " a LEFT JOIN " + tableB.getName() + " b ON (" + onCond + ") WHERE " + nullRejectCond;
            String q2 = "SELECT DISTINCT a." + colA.getName() + ", b." + colB.getName() + " FROM " + tableA.getName()
                    + " a INNER JOIN " + tableB.getName() + " b ON (" + onCond + ") WHERE " + nullRejectCond;
            bundle = new JIRBundle(mode, q1, q2);
            break;
        }
        case INNER_INCLUSION_PROJECTION: {
            String onCond = "a." + colA.getName() + " = b." + colB.getName();
            String qInner = "SELECT DISTINCT a." + colA.getName() + " FROM " + tableA.getName() + " a INNER JOIN "
                    + tableB.getName() + " b ON (" + onCond + ")";
            String qLeft = "SELECT DISTINCT a." + colA.getName() + " FROM " + tableA.getName() + " a LEFT JOIN "
                    + tableB.getName() + " b ON (" + onCond + ")";
            bundle = new JIRBundle(mode, qInner, qLeft);
            break;
        }
        case TRANSITIVE_EQUIJOIN_IMPLICATION: {
            GaussDBTable tableC = Randomly.fromList(allTables);
            GaussDBColumn colC = Randomly.fromList(tableC.getColumns());
            String q1 = "SELECT a." + colA.getName() + ", b." + colB.getName() + ", c." + colC.getName() + " FROM "
                    + tableA.getName() + " a JOIN " + tableB.getName() + " b ON a." + colA.getName() + " = b."
                    + colB.getName() + " JOIN " + tableC.getName() + " c ON b." + colB.getName() + " = c."
                    + colC.getName();
            String q2 = "SELECT a." + colA.getName() + ", b." + colB.getName() + ", c." + colC.getName() + " FROM "
                    + tableA.getName() + " a JOIN " + tableB.getName() + " b ON a." + colA.getName() + " = b."
                    + colB.getName() + " JOIN " + tableC.getName() + " c ON a." + colA.getName() + " = c."
                    + colC.getName();
            bundle = new JIRBundle(mode, q1, q2);
            break;
        }
        case SELF_JOIN_IDENTITY: {
            String colName = colA.getName();
            String q1 = "SELECT DISTINCT a." + colName + " FROM " + tableA.getName() + " a JOIN " + tableA.getName()
                    + " a2 ON a." + colName + " = a2." + colName + " WHERE a." + colName + " IS NOT NULL";
            String q2 = "SELECT DISTINCT a." + colName + " FROM " + tableA.getName() + " a WHERE a." + colName
                    + " IS NOT NULL";
            bundle = new JIRBundle(mode, q1, q2);
            break;
        }
        default:
            throw new IgnoreMeException();
        }

        SQLConnection connection = state.getConnection();
        try (Statement statement = connection.createStatement()) {
            List<String> r1 = executeAndGetRows(statement, bundle.getQuery1());
            List<String> r2 = executeAndGetRows(statement, bundle.getQuery2());

            try {
                verifyJIRInvariants(bundle, r1, r2);
            } catch (AssertionError e) {
                // Retry once to eliminate non-deterministic state
                r1 = executeAndGetRows(statement, bundle.getQuery1());
                r2 = executeAndGetRows(statement, bundle.getQuery2());
                try {
                    verifyJIRInvariants(bundle, r1, r2);
                } catch (AssertionError ex) {
                    reproducer = new GaussDBJIRReproducer(bundle);
                    throw ex;
                }
            }
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        }
    }

    public static void verifyJIRInvariants(JIRBundle bundle, List<String> r1, List<String> r2) {
        switch (bundle.getMode()) {
        case NULL_REJECTING_OUTER_TO_INNER:
        case SELF_JOIN_IDENTITY: {
            Set<String> s1 = new HashSet<>(r1);
            Set<String> s2 = new HashSet<>(r2);
            if (!s1.equals(s2)) {
                throw new AssertionError(String.format(
                        "JIR Equivalence Violation (%s)!\nQuery1: %s\nRows: %s\nQuery2: %s\nRows: %s",
                        bundle.getMode(), bundle.getQuery1(), r1, bundle.getQuery2(), r2));
            }
            break;
        }
        case INNER_INCLUSION_PROJECTION: {
            Set<String> innerSet = new HashSet<>(r1);
            Set<String> leftSet = new HashSet<>(r2);
            if (!leftSet.containsAll(innerSet)) {
                Set<String> diff = new HashSet<>(innerSet);
                diff.removeAll(leftSet);
                throw new AssertionError(String.format(
                        "JIR Inclusion Violation: Inner Join projected rows not found in Left Join!\n"
                                + "Missing: %s\nQueryInner: %s\nQueryLeft: %s",
                        diff, bundle.getQuery1(), bundle.getQuery2()));
            }
            break;
        }
        case TRANSITIVE_EQUIJOIN_IMPLICATION: {
            Set<String> s1 = new HashSet<>(r1);
            Set<String> s2 = new HashSet<>(r2);
            if (!s1.equals(s2)) {
                throw new AssertionError(String.format(
                        "JIR Transitive Equi-Join Violation!\nExplicit: %s\nRows: %s\nImplied: %s\nRows: %s",
                        bundle.getQuery1(), r1, bundle.getQuery2(), r2));
            }
            break;
        }
        default:
            break;
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
