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
        errors.addRegexString("(?i)out of (?:the )?range");
        errors.addRegexString("(?i)cannot convert (?:Infinity|NaN) to (?:numeric|bigint)");
        errors.add("value too long");
        errors.add("cannot cast");
        errors.add("fail to update partitioned table");
        errors.add("inserted partition key does not map to any table partition");
        errors.add("updated partition key does not map to any table partition");
        errors.add("invalid byte sequence for encoding");
        errors.add("null character not permitted");
        errors.add("memory is temporarily unavailable");
        // Connection & restart transient errors to survive DB restarts / OOM-recoveries
        errors.add("Connection refused");
        errors.add("Connection to");
        errors.add("refused");
        errors.add("Connection reset");
        errors.add("broken pipe");
        errors.add("This connection has been closed");
        errors.add("An I/O error occurred while sending to the backend");
        errors.add("An I/O error occurred while reading from the backend");
        errors.add("the database system is starting up");
        errors.add("the database system is in recovery mode");
        errors.add("the database system is shutting down");
        errors.add("terminating connection due to administrator command");
        errors.add("FATAL: terminating connection");
        errors.add("sorry, too many clients already");
        errors.add("Socket closed");
    }

    public static void addCommonFetchErrors(ExpectedErrors errors) {
        errors.add("canceling statement due to statement timeout");
        errors.add("current transaction is aborted");
        errors.add("memory is temporarily unavailable");
        // Connection & restart transient errors
        errors.add("Connection refused");
        errors.add("Connection to");
        errors.add("refused");
        errors.add("Connection reset");
        errors.add("broken pipe");
        errors.add("This connection has been closed");
        errors.add("An I/O error occurred while sending to the backend");
        errors.add("An I/O error occurred while reading from the backend");
        errors.add("the database system is starting up");
        errors.add("the database system is in recovery mode");
        errors.add("the database system is shutting down");
        errors.add("terminating connection due to administrator command");
        errors.add("FATAL: terminating connection");
        errors.add("sorry, too many clients already");
        errors.add("Socket closed");
    }

}
