package sqlancer.gaussdb.gen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.gaussdb.GaussDBGlobalState;

public final class GaussDBSetGenerator {

    private static final String[] BOOLEAN_OPTIMIZER_GUCS = { "enable_bitmapscan", "enable_hashagg", "enable_hashjoin",
            "enable_indexscan", "enable_indexonlyscan", "enable_material", "enable_mergejoin", "enable_nestloop",
            "enable_opfusion", "enable_partitionwise", "enable_seqscan", "enable_sonic_hashagg",
            "enable_sonic_hashjoin", "enable_sort", "enable_tidscan" };

    private static final String[] REWRITE_RULES = { "lazyagg", "magicset", "partialpush", "uniquecheck", "intargetlist",
            "predpush", "predpushforce", "enable_sublink_pullup_enhanced", "groupby_pushdown_subquery",
            "cse_rewrite_opt", "pullup_in_related", "enable_corr_any_sublink_semijoin_to_innerjoin",
            "enable_sublink_pullup_rownum", "enable_not_in_sublink_reduce_distinct" };

    private GaussDBSetGenerator() {
    }

    public static SQLQueryAdapter set(GaussDBGlobalState globalState) {
        String sql;
        switch (Randomly.fromOptions(0, 1, 2)) {
        case 0:
            String guc = Randomly.fromOptions(BOOLEAN_OPTIMIZER_GUCS);
            String val = Randomly.fromOptions("on", "off");
            sql = "SET " + guc + " = " + val;
            break;
        case 1:
            int dop = Randomly.fromOptions(1, 2, 4);
            sql = "SET query_dop = " + dop;
            break;
        case 2:
            if (Randomly.getBooleanWithSmallProbability()) {
                sql = "SET rewrite_rule = 'none'";
            } else {
                boolean bypassKnownBugs = globalState.getDbmsSpecificOptions() == null
                        || globalState.getDbmsSpecificOptions().bypassBooleanTestRangeBug;
                List<String> rules = Randomly.nonEmptySubset(REWRITE_RULES).stream()
                        .filter(r -> !bypassKnownBugs || !"cse_rewrite_opt".equals(r)).collect(Collectors.toList());
                if (rules.isEmpty()) {
                    sql = "SET rewrite_rule = 'none'";
                } else {
                    sql = "SET rewrite_rule = '" + rules.stream().collect(Collectors.joining(",")) + "'";
                }
            }
            break;
        default:
            throw new AssertionError();
        }
        ExpectedErrors errors = new ExpectedErrors();
        errors.add("unrecognized configuration parameter");
        errors.add("invalid value for parameter");
        errors.add("cannot be changed");
        return new SQLQueryAdapter(sql, errors);
    }

}
