package sqlancer.gaussdb;

import java.sql.SQLException;

import sqlancer.OracleFactory;
import sqlancer.common.oracle.TestOracle;
import sqlancer.gaussdb.oracle.GaussDBCODDTestOracle;
import sqlancer.gaussdb.oracle.GaussDBDQEOracle;
import sqlancer.gaussdb.oracle.GaussDBDQPOracle;
import sqlancer.gaussdb.oracle.GaussDBEETOracle;
import sqlancer.gaussdb.oracle.GaussDBEPSCOracle;
import sqlancer.gaussdb.oracle.GaussDBNoRECOracle;
import sqlancer.gaussdb.oracle.GaussDBTLPOracle;
import sqlancer.gaussdb.oracle.GaussDBThanosOracle;

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
    },

    EPSC {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBEPSCOracle(globalState);
        }
    },

    THANOS {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBThanosOracle(globalState);
        }
    },

    DQE {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBDQEOracle(globalState);
        }
    },

    NOREC {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBNoRECOracle(globalState);
        }
    },

    TLP {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBTLPOracle(globalState);
        }
    },

    DQP {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBDQPOracle(globalState);
        }
    }

}
