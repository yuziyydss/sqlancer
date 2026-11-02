package sqlancer.gaussdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.gen.GaussDBExpressionGenerator;

class GaussDBExpressionGeneratorTest {

    @Test
    void generatesBooleanExpression() {
        GaussDBGlobalState state = new GaussDBGlobalState();
        state.setMainOptions(new MainOptions());
        state.setRandomly(new Randomly(0));

        GaussDBColumn column = new GaussDBColumn("c0", GaussDBDataType.INT);
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(List.of(column));

        GaussDBExpression expression = generator.generateExpression(GaussDBDataType.BOOLEAN);

        assertEquals(GaussDBDataType.BOOLEAN, expression.getExpressionType());
        assertFalse(GaussDBToStringVisitor.asString(expression).isBlank());
    }

    @Test
    void generatesNumericExpression() {
        GaussDBGlobalState state = new GaussDBGlobalState();
        state.setMainOptions(new MainOptions());
        state.setRandomly(new Randomly(1));

        GaussDBColumn column = new GaussDBColumn("c0", GaussDBDataType.INT);
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(List.of(column));

        GaussDBExpression expression = generator.generateExpression(GaussDBDataType.INT);

        assertEquals(GaussDBDataType.INT, expression.getExpressionType());
        assertFalse(GaussDBToStringVisitor.asString(expression).isBlank());
    }

    @Test
    void generatesTemporalExpressions() {
        GaussDBGlobalState state = new GaussDBGlobalState();
        state.setMainOptions(new MainOptions());
        state.setRandomly(new Randomly(2));

        GaussDBColumn dateColumn = new GaussDBColumn("c0", GaussDBDataType.DATE);
        GaussDBColumn timestampColumn = new GaussDBColumn("c1", GaussDBDataType.TIMESTAMP);
        GaussDBColumn intervalColumn = new GaussDBColumn("c2", GaussDBDataType.INTERVAL);
        GaussDBColumn timestamptzColumn = new GaussDBColumn("c3", GaussDBDataType.TIMESTAMPTZ);
        GaussDBColumn timeColumn = new GaussDBColumn("c4", GaussDBDataType.TIME);
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(List.of(dateColumn, timestampColumn, intervalColumn, timestamptzColumn, timeColumn));

        assertEquals(GaussDBDataType.DATE,
                generator.generateExpression(GaussDBDataType.DATE).getExpressionType());
        assertEquals(GaussDBDataType.TIMESTAMP,
                generator.generateExpression(GaussDBDataType.TIMESTAMP).getExpressionType());
        assertEquals(GaussDBDataType.INTERVAL,
                generator.generateExpression(GaussDBDataType.INTERVAL).getExpressionType());
        assertEquals(GaussDBDataType.TIMESTAMPTZ,
                generator.generateExpression(GaussDBDataType.TIMESTAMPTZ).getExpressionType());
        assertEquals(GaussDBDataType.TIME, generator.generateExpression(GaussDBDataType.TIME).getExpressionType());
    }

    @Test
    void timeExpressionsAvoidDateTrunc() {
        GaussDBGlobalState state = new GaussDBGlobalState();
        state.setMainOptions(new MainOptions());
        state.setRandomly(new Randomly(7));

        GaussDBColumn timeColumn = new GaussDBColumn("c0", GaussDBDataType.TIME);
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(List.of(timeColumn));

        for (int i = 0; i < 100; i++) {
            String expression = GaussDBToStringVisitor
                    .asString(generator.generateExpression(GaussDBDataType.TIME));
            assertFalse(expression.contains("date_trunc"), expression);
        }
    }

    @Test
    void bypassBooleanTestRangeBugAvoidsBooleanNullTestsAndCoalesceNvl() {
        GaussDBGlobalState state = new GaussDBGlobalState();
        state.setMainOptions(new MainOptions());
        GaussDBOptions options = new GaussDBOptions();
        options.bypassBooleanTestRangeBug = true;
        state.setDbmsSpecificOptions(options);
        state.setRandomly(new Randomly(42));

        GaussDBColumn intColumn = new GaussDBColumn("c0", GaussDBDataType.INT);
        GaussDBColumn boolColumn = new GaussDBColumn("c1", GaussDBDataType.BOOLEAN);
        GaussDBExpressionGenerator generator = new GaussDBExpressionGenerator(state);
        generator.setColumns(List.of(intColumn, boolColumn));

        for (int i = 0; i < 200; i++) {
            String expression = GaussDBToStringVisitor
                    .asString(generator.generateExpression(GaussDBDataType.BOOLEAN));
            assertFalse(expression.contains("IS FALSE"), expression);
            assertFalse(expression.contains("IS NOT FALSE"), expression);
        }
    }

}
