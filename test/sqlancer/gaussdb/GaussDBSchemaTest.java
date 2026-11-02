package sqlancer.gaussdb;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GaussDBSchemaTest {

    @Test
    void isViewTableTypeUsesInformationSchemaTableTypeNotNamePrefix() {
        assertTrue(GaussDBSchema.isViewTableType("VIEW"));
        assertTrue(GaussDBSchema.isViewTableType(" view "));
        assertFalse(GaussDBSchema.isViewTableType("BASE TABLE"));
        assertFalse(GaussDBSchema.isViewTableType("LOCAL TEMPORARY"));
        assertFalse(GaussDBSchema.isViewTableType(null));
    }

}
