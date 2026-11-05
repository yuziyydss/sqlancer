package sqlancer.gaussdb.ast;

import java.math.BigDecimal;

import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;

public abstract class GaussDBConstant implements GaussDBExpression {

    public static GaussDBConstant createNullConstant() {
        return new GaussDBNullConstant();
    }

    public static GaussDBConstant createIntConstant(long val) {
        return new IntConstant(val);
    }

    public static GaussDBConstant createBooleanConstant(boolean val) {
        return new BooleanConstant(val);
    }

    public static GaussDBConstant createTrue() {
        return createBooleanConstant(true);
    }

    public static GaussDBConstant createFalse() {
        return createBooleanConstant(false);
    }

    public static GaussDBConstant createTextConstant(String string) {
        return new StringConstant(string);
    }

    public static GaussDBConstant createDecimalConstant(BigDecimal bigDecimal) {
        return new DecimalConstant(bigDecimal);
    }

    public static GaussDBConstant createDateConstant(String value) {
        return new DateConstant(value);
    }

    public static GaussDBConstant createTimestampConstant(String value) {
        return new TimestampConstant(value);
    }

    public static GaussDBConstant createTimestamptzConstant(String value) {
        return new TimestamptzConstant(value);
    }

    public static GaussDBConstant createTimeConstant(String value) {
        return new TimeConstant(value);
    }

    public static GaussDBConstant createIntervalConstant(String value) {
        return new IntervalConstant(value);
    }

    public static GaussDBConstant createRawConstant(String rawText, GaussDBDataType type) {
        return new RawConstant(rawText, type);
    }

    public abstract String getTextRepresentation();

    public boolean isNull() {
        return false;
    }

    public boolean asBoolean() {
        throw new UnsupportedOperationException(this.toString());
    }

    public long asInt() {
        throw new UnsupportedOperationException(this.toString());
    }

    public boolean isBoolean() {
        return false;
    }

    public boolean isInt() {
        return false;
    }

    public boolean isString() {
        return false;
    }

    @Override
    public abstract GaussDBDataType getExpressionType();

    @Override
    public String toString() {
        return getTextRepresentation();
    }

    public static class BooleanConstant extends GaussDBConstant {

        private final boolean value;

        public BooleanConstant(boolean value) {
            this.value = value;
        }

        @Override
        public String getTextRepresentation() {
            return value ? "TRUE" : "FALSE";
        }

        @Override
        public boolean asBoolean() {
            return value;
        }

        @Override
        public boolean isBoolean() {
            return true;
        }

        @Override
        public GaussDBDataType getExpressionType() {
            return GaussDBDataType.BOOLEAN;
        }

    }

    public static class GaussDBNullConstant extends GaussDBConstant {

        @Override
        public String getTextRepresentation() {
            return "NULL";
        }

        @Override
        public boolean isNull() {
            return true;
        }

        @Override
        public GaussDBDataType getExpressionType() {
            return null;
        }

    }

    public static class IntConstant extends GaussDBConstant {

        private final long value;

        public IntConstant(long value) {
            this.value = value;
        }

        @Override
        public String getTextRepresentation() {
            return String.valueOf(value);
        }

        @Override
        public long asInt() {
            return value;
        }

        @Override
        public boolean isInt() {
            return true;
        }

        @Override
        public GaussDBDataType getExpressionType() {
            return GaussDBDataType.INT;
        }

    }

    public static class DecimalConstant extends GaussDBConstant {

        private final BigDecimal value;

        public DecimalConstant(BigDecimal value) {
            this.value = value;
        }

        @Override
        public String getTextRepresentation() {
            return value.toPlainString();
        }

        @Override
        public GaussDBDataType getExpressionType() {
            return GaussDBDataType.DECIMAL;
        }

    }

    public static class StringConstant extends GaussDBConstant {

        private final String value;

        public StringConstant(String value) {
            this.value = value;
        }

        @Override
        public String getTextRepresentation() {
            return String.format("'%s'", value.replace("'", "''"));
        }

        @Override
        public boolean isString() {
            return true;
        }

        @Override
        public GaussDBDataType getExpressionType() {
            return GaussDBDataType.TEXT;
        }

    }

    public static class DateConstant extends GaussDBConstant {

        private final String value;

        public DateConstant(String value) {
            this.value = value;
        }

        @Override
        public String getTextRepresentation() {
            return String.format("to_date('%s', 'YYYY-MM-DD')", value.replace("'", "''"));
        }

        @Override
        public GaussDBDataType getExpressionType() {
            return GaussDBDataType.DATE;
        }

    }

    public static class TimestampConstant extends GaussDBConstant {

        private final String value;

        public TimestampConstant(String value) {
            this.value = value;
        }

        @Override
        public String getTextRepresentation() {
            return String.format("TIMESTAMP '%s'", value.replace("'", "''"));
        }

        @Override
        public GaussDBDataType getExpressionType() {
            return GaussDBDataType.TIMESTAMP;
        }

    }

    public static class TimestamptzConstant extends GaussDBConstant {

        private final String value;

        public TimestamptzConstant(String value) {
            this.value = value;
        }

        @Override
        public String getTextRepresentation() {
            return String.format("TIMESTAMPTZ '%s'", value.replace("'", "''"));
        }

        @Override
        public GaussDBDataType getExpressionType() {
            return GaussDBDataType.TIMESTAMPTZ;
        }

    }

    public static class TimeConstant extends GaussDBConstant {

        private final String value;

        public TimeConstant(String value) {
            this.value = value;
        }

        @Override
        public String getTextRepresentation() {
            return String.format("TIME '%s'", value.replace("'", "''"));
        }

        @Override
        public GaussDBDataType getExpressionType() {
            return GaussDBDataType.TIME;
        }

    }

    public static class IntervalConstant extends GaussDBConstant {

        private final String value;

        public IntervalConstant(String value) {
            this.value = value;
        }

        @Override
        public String getTextRepresentation() {
            return String.format("INTERVAL '%s'", value.replace("'", "''"));
        }

        @Override
        public GaussDBDataType getExpressionType() {
            return GaussDBDataType.INTERVAL;
        }

    }

    public static class RawConstant extends GaussDBConstant {

        private final String rawText;
        private final GaussDBDataType type;

        public RawConstant(String rawText, GaussDBDataType type) {
            this.rawText = rawText;
            this.type = type;
        }

        @Override
        public String getTextRepresentation() {
            return rawText;
        }

        @Override
        public GaussDBDataType getExpressionType() {
            return type;
        }

    }

}
