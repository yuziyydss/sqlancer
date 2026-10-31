package sqlancer.gaussdb;

import sqlancer.SQLGlobalState;

public class GaussDBGlobalState extends SQLGlobalState<GaussDBOptions, GaussDBSchema> {

    @Override
    public GaussDBSchema readSchema() throws java.sql.SQLException {
        return GaussDBSchema.fromConnection(getConnection(), getDatabaseName());
    }

}
