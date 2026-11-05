package sqlancer.gaussdb;

import sqlancer.SQLGlobalState;
import sqlancer.gaussdb.GaussDBOptions.CompatibilityMode;

public class GaussDBGlobalState extends SQLGlobalState<GaussDBOptions, GaussDBSchema> {

    private CompatibilityMode compatibilityMode = CompatibilityMode.A;

    @Override
    public GaussDBSchema readSchema() throws java.sql.SQLException {
        return GaussDBSchema.fromConnection(getConnection(), getDatabaseName());
    }

    public CompatibilityMode getCompatibilityMode() {
        return compatibilityMode;
    }

    public void setCompatibilityMode(CompatibilityMode compatibilityMode) {
        this.compatibilityMode = compatibilityMode;
    }

}
