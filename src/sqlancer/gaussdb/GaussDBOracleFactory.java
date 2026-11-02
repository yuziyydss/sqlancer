package sqlancer.gaussdb;

import java.sql.SQLException;

import sqlancer.OracleFactory;
import sqlancer.common.oracle.TestOracle;
import sqlancer.gaussdb.oracle.GaussDBCODDTestOracle;
import sqlancer.gaussdb.oracle.GaussDBEETOracle;

public enum GaussDBOracleFactory implements OracleFactory<GaussDBGlobalState> {

    CODDTEST {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBCODDTestOracle(globalState);
        }

        @Override
        public boolean requiresAllTablesToContainRows() {
            return true;
        }
    },

    EET {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBEETOracle(globalState);
        }
    }

}
