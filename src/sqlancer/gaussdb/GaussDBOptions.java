package sqlancer.gaussdb;

import java.util.Arrays;
import java.util.List;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import sqlancer.DBMSSpecificOptions;

@Parameters(separators = "=", commandDescription = "GaussDB/openGauss (PG-compatible mode, default port: "
        + GaussDBOptions.DEFAULT_PORT + ", default host: " + GaussDBOptions.DEFAULT_HOST + ")")
public class GaussDBOptions implements DBMSSpecificOptions<GaussDBOracleFactory> {

    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 19995;

    @Parameter(names = "--oracle", description = "Specifies which test oracle should be used for GaussDB")
    public List<GaussDBOracleFactory> oracle = Arrays.asList(GaussDBOracleFactory.CODDTEST);

    @Parameter(names = "--connection-url", description = "Specifies the JDBC URL for connecting to GaussDB/openGauss", arity = 1)
    public String connectionURL = String.format("opengauss://%s:%d/postgres", DEFAULT_HOST, DEFAULT_PORT);

    @Parameter(names = "--use-schema-isolation", description = "Use schema isolation instead of CREATE DATABASE, useful for cloud-managed databases", arity = 1)
    public boolean useSchemaIsolation = true;

    @Parameter(names = "--max-num-tables", description = "The maximum number of tables that can be created")
    public int maxNumTables = 4;

    @Parameter(names = "--bypass-boolean-test-range-bug", description = "Bypass known GaussDB BooleanTest (IS FALSE) range-flattening bug in TLP by conjoining IS NOT NULL", arity = 1)
    public boolean bypassBooleanTestRangeBug = true;

    @Override
    public List<GaussDBOracleFactory> getTestOracleFactory() {
        return oracle;
    }

}
