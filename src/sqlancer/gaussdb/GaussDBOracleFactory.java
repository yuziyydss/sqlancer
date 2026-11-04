package sqlancer.gaussdb;

import java.sql.SQLException;

import sqlancer.OracleFactory;
import sqlancer.common.oracle.TestOracle;
import sqlancer.gaussdb.oracle.GaussDBARGOracle;
import sqlancer.gaussdb.oracle.GaussDBCERTOracle;
import sqlancer.gaussdb.oracle.GaussDBCODDTestOracle;
import sqlancer.gaussdb.oracle.GaussDBDDLCheckOracle;
import sqlancer.gaussdb.oracle.GaussDBEDCOracle;
import sqlancer.gaussdb.oracle.GaussDBWSSOracle;
import sqlancer.gaussdb.oracle.GaussDBDQEOracle;
import sqlancer.gaussdb.oracle.GaussDBDQPOracle;
import sqlancer.gaussdb.oracle.GaussDBEETOracle;
import sqlancer.gaussdb.oracle.GaussDBERIQOracle;
import sqlancer.gaussdb.oracle.GaussDBSRSOracle;
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
    },

    ERIQ {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBERIQOracle(globalState);
        }
    },

    SRS {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBSRSOracle(globalState);
        }
    },

    ARG {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBARGOracle(globalState);
        }
    },

    CERT {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBCERTOracle(globalState);
        }
    },

    DDLCHECK {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBDDLCheckOracle(globalState);
        }
    },

    EDC {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBEDCOracle(globalState);
        }
    },

    WSS {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBWSSOracle(globalState);
        }
    }

}
