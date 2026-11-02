package sqlancer.gaussdb;

import sqlancer.common.query.ExpectedErrors;

public final class GaussDBErrors {

    private GaussDBErrors() {
    }

    public static void addCommonExpressionErrors(ExpectedErrors errors) {
        errors.addRegexString("(?i)division by zero");
        errors.add("CASE types");
        errors.add("operator does not exist");
        errors.add("function does not exist");
        errors.add("invalid input syntax");
        errors.add("out of range");
        errors.add("value too long");
        errors.add("cannot cast");
    }

    public static void addCommonFetchErrors(ExpectedErrors errors) {
        errors.add("canceling statement due to statement timeout");
        errors.add("current transaction is aborted");
        errors.add("memory is temporarily unavailable");
    }

}
