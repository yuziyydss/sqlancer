package sqlancer.gaussdb;

import sqlancer.common.query.ExpectedErrors;

public final class GaussDBErrors {

    private GaussDBErrors() {
    }

    public static void addCommonExpressionErrors(ExpectedErrors errors) {
        errors.addRegexString("(?i)division by zero");
        errors.add("CASE types");
        errors.add("GREATEST types");
        errors.add("LEAST types");
        errors.add("NVL types");
        errors.add("COALESCE types");
        errors.add("operator does not exist");
        errors.add("function does not exist");
        errors.add("invalid input syntax");
        errors.add("The input syntax of type");
        errors.add("invalid regular expression");
        errors.add("negative substring length not allowed");
        errors.add("out of range");
        errors.add("value too long");
        errors.add("cannot cast");
        errors.add("fail to update partitioned table");
        errors.add("inserted partition key does not map to any table partition");
        errors.add("updated partition key does not map to any table partition");
        errors.add("invalid byte sequence for encoding");
        errors.add("null character not permitted");
        errors.add("memory is temporarily unavailable");
    }

    public static void addCommonFetchErrors(ExpectedErrors errors) {
        errors.add("canceling statement due to statement timeout");
        errors.add("current transaction is aborted");
        errors.add("memory is temporarily unavailable");
    }

}
