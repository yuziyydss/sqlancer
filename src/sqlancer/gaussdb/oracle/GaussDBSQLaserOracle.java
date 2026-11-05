package sqlancer.gaussdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * SQLaser (USENIX Security / arXiv) Clause-Combination-Guided Oracle for GaussDB.
 *
 * <p>
 * Stresses complex SQL clause combinations (WITH Common Table Expressions, WINDOW functions,
 * GROUPING SETS / ROLLUP, and QUALIFY / HAVING filtering):
 * 1. GROUPING_SETS_UNION_ALL: Validates that GROUPING SETS ((c1), (c2)) matches the UNION ALL of individual aggregations.
 * 2. CTE_WINDOW_MIN_REDUCTION: Validates that a CTE with ROW_NUMBER() OVER (PARTITION BY c1 ORDER BY c2) filtered by rn=1
 *    matches the GROUP BY c1, MIN(c2) projection.
 *
 * Catches deep bugs in hash aggregation spilling, window frame navigation, CTE inlining, and null grouping sets.
 * </p>
 */
public class GaussDBSQLaserOracle implements TestOracle<GaussDBGlobalState> {

    private final GaussDBGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private GaussDBSQLaserReproducer reproducer;

    public GaussDBSQLaserOracle(GaussDBGlobalState state) {
        this.state = state;
        GaussDBErrors.addCommonExpressionErrors(errors);
        GaussDBErrors.addCommonFetchErrors(errors);
    }

    public enum SQLaserMode {
        GROUPING_SETS_UNION_ALL,
        CTE_WINDOW_MIN_REDUCTION
    }

    public static final class SQLaserBundle {

        private final SQLaserMode mode;
        private final String query1;
        private final String query2;

        public SQLaserBundle(SQLaserMode mode, String query1, String query2) {
            this.mode = mode;
            this.query1 = query1;
            this.query2 = query2;
        }

        public SQLaserMode getMode() {
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
        GaussDBTable table = state.getSchema()
                .getRandomTableOrBailout(candidate -> !candidate.isView() && candidate.getColumns().size() >= 2);

        List<GaussDBColumn> cols = table.getColumns();
        if (cols.size() < 2) {
            throw new IgnoreMeException();
        }

        GaussDBColumn c1 = cols.get(0);
        GaussDBColumn c2 = cols.get(1);
        String t = table.getName();

        SQLaserMode mode = Randomly.fromOptions(SQLaserMode.values());
        String q1;
        String q2;

        switch (mode) {
        case GROUPING_SETS_UNION_ALL:
            q1 = "SELECT " + c1.getName() + ", " + c2.getName() + ", COUNT(*) FROM " + t + " GROUP BY GROUPING SETS (("
                    + c1.getName() + "), (" + c2.getName() + "))";
            q2 = "(SELECT " + c1.getName() + ", CAST(NULL AS " + c2.getType() + ") AS " + c2.getName()
                    + ", COUNT(*) FROM " + t + " GROUP BY " + c1.getName() + ") UNION ALL (SELECT CAST(NULL AS "
                    + c1.getType() + ") AS " + c1.getName() + ", " + c2.getName() + ", COUNT(*) FROM " + t
                    + " GROUP BY " + c2.getName() + ")";
            break;
        case CTE_WINDOW_MIN_REDUCTION:
            q1 = "WITH cte AS (SELECT " + c1.getName() + ", " + c2.getName() + ", ROW_NUMBER() OVER (PARTITION BY "
                    + c1.getName() + " ORDER BY " + c2.getName() + " ASC NULLS LAST) AS rn FROM " + t
                    + " WHERE " + c2.getName() + " IS NOT NULL) SELECT " + c1.getName() + ", " + c2.getName()
                    + " FROM cte WHERE rn = 1";
            q2 = "SELECT " + c1.getName() + ", MIN(" + c2.getName() + ") FROM " + t + " WHERE " + c2.getName()
                    + " IS NOT NULL GROUP BY " + c1.getName();
            break;
        default:
            throw new IgnoreMeException();
        }

        SQLaserBundle bundle = new SQLaserBundle(mode, q1, q2);
        SQLConnection connection = state.getConnection();

        try (Statement statement = connection.createStatement()) {
            List<String> r1 = executeAndGetRows(statement, q1);
            List<String> r2 = executeAndGetRows(statement, q2);

            try {
                verifySQLaserInvariants(bundle, r1, r2);
            } catch (AssertionError e) {
                // Retry once
                r1 = executeAndGetRows(statement, q1);
                r2 = executeAndGetRows(statement, q2);
                try {
                    verifySQLaserInvariants(bundle, r1, r2);
                } catch (AssertionError ex) {
                    reproducer = new GaussDBSQLaserReproducer(bundle);
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

    public static void verifySQLaserInvariants(SQLaserBundle bundle, List<String> r1, List<String> r2) {
        Map<String, Integer> map1 = toCountMap(r1);
        Map<String, Integer> map2 = toCountMap(r2);

        if (!map1.equals(map2)) {
            throw new AssertionError(String.format(
                    "SQLaser Clause-Combination Equivalence Violation (%s)!\n"
                            + "Query1: %s\nMultiset1 (%d): %s\nQuery2: %s\nMultiset2 (%d): %s",
                    bundle.getMode(), bundle.getQuery1(), r1.size(), r1,
                    bundle.getQuery2(), r2.size(), r2));
        }
    }

    private static Map<String, Integer> toCountMap(List<String> list) {
        Map<String, Integer> map = new HashMap<>();
        for (String item : list) {
            map.put(item, map.getOrDefault(item, 0) + 1);
        }
        return map;
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
