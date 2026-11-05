package sqlancer.gaussdb;

import java.sql.SQLException;

import sqlancer.OracleFactory;
import sqlancer.common.oracle.TestOracle;
import sqlancer.gaussdb.oracle.GaussDBARGOracle;
import sqlancer.gaussdb.oracle.GaussDBAccioOracle;
import sqlancer.gaussdb.oracle.GaussDBBoundaryOracle;
import sqlancer.gaussdb.oracle.GaussDBCERTOracle;
import sqlancer.gaussdb.oracle.GaussDBCODDTestOracle;
import sqlancer.gaussdb.oracle.GaussDBDDLCheckOracle;
import sqlancer.gaussdb.oracle.GaussDBDDLumosOracle;
import sqlancer.gaussdb.oracle.GaussDBDistSQLOracle;
import sqlancer.gaussdb.oracle.GaussDBEDCOracle;
import sqlancer.gaussdb.oracle.GaussDBFucciOracle;
import sqlancer.gaussdb.oracle.GaussDBISORelOracle;
import sqlancer.gaussdb.oracle.GaussDBIVMOracle;
import sqlancer.gaussdb.oracle.GaussDBJITOracle;
import sqlancer.gaussdb.oracle.GaussDBJIROracle;
import sqlancer.gaussdb.oracle.GaussDBPLSQLOracle;
import sqlancer.gaussdb.oracle.GaussDBPinoloOracle;
import sqlancer.gaussdb.oracle.GaussDBPuppyOracle;
import sqlancer.gaussdb.oracle.GaussDBSCorOracle;
import sqlancer.gaussdb.oracle.GaussDBSQLaserOracle;
import sqlancer.gaussdb.oracle.GaussDBSedarOracle;
import sqlancer.gaussdb.oracle.GaussDBTxCheckOracle;
import sqlancer.gaussdb.oracle.GaussDBValScopeOracle;
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
    },

    JIT {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBJITOracle(globalState);
        }
    },

    BOUNDARY {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBBoundaryOracle(globalState);
        }
    },

    VALSCOPE {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBValScopeOracle(globalState);
        }
    },

    PLSQL {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBPLSQLOracle(globalState);
        }
    },

    PINOLO {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBPinoloOracle(globalState);
        }
    },

    JIR {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBJIROracle(globalState);
        }
    },

    SCOR {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBSCorOracle(globalState);
        }
    },

    PUPPY {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBPuppyOracle(globalState);
        }
    },

    DDLUMOS {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBDDLumosOracle(globalState);
        }
    },

    SQLASER {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBSQLaserOracle(globalState);
        }
    },

    ISOREL {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBISORelOracle(globalState);
        }
    },

    TXCHECK {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBTxCheckOracle(globalState);
        }
    },

    FUCCI {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBFucciOracle(globalState);
        }
    },

    SEDAR {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBSedarOracle(globalState);
        }
    },

    DISTSQL {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBDistSQLOracle(globalState);
        }
    },

    IVM {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBIVMOracle(globalState);
        }
    },

    ACCIO {
        @Override
        public TestOracle<GaussDBGlobalState> create(GaussDBGlobalState globalState) throws SQLException {
            return new GaussDBAccioOracle(globalState);
        }
    }

}
