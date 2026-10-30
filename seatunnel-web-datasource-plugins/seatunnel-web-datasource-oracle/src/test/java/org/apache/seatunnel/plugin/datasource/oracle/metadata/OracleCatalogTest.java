package org.apache.seatunnel.plugin.datasource.oracle.metadata;

import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.oracle.param.OracleConnectionParam;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleCatalogTest {

    @Test
    void listTableSqlReturnsOwnerQualifiedTablePath() {
        OracleCatalog catalog = new OracleCatalog(new OracleConnectionParam(), null);

        String sql = catalog.getListTableSql("ORCL");

        assertTrue(sql.contains("OWNER || '.' || TABLE_NAME AS table_path"));
        assertTrue(sql.contains("OWNER NOT IN ("));
        assertTrue(sql.contains("'SYS'"));
        assertTrue(sql.contains("ORDER BY OWNER, TABLE_NAME"));
        assertTrue(!sql.contains("ORACLE_MAINTAINED"));
    }

    @Test
    void buildTableReferenceUsesSchemaFromTablePath() {
        OracleConnectionParam param = new OracleConnectionParam();
        param.setUser("SYSTEM");
        OracleCatalog catalog = new OracleCatalog(param, null);

        String reference = catalog.buildTableReference(
                TablePath.of("ORCL", "ORACLE_APP", "TEST_USER"));

        assertEquals("\"ORACLE_APP\".\"TEST_USER\"", reference);
    }

    @Test
    void preservesMixedCaseOwnerAndEscapesEmbeddedIdentifierQuotes() {
        OracleCatalog catalog = new OracleCatalog(new OracleConnectionParam(), null);

        assertEquals("\"MiXeD_Owner\".\"Order\"\"Details\"", catalog.buildTableReference(
                TablePath.of("ORCL", "MiXeD_Owner", "Order\"Details")));

        String sql = catalog.getSelectColumnsSql(
                TablePath.of("ORCL", "MiXeD_Owner", "OrderDetails"));
        assertTrue(sql.contains("OWNER = 'MiXeD_Owner'"));
        assertTrue(sql.contains("TABLE_NAME = 'OrderDetails'"));
    }

    @Test
    void foldsConfiguredSchemaFallbackButPreservesQualifiedMetadataNames() throws SQLException {
        OracleConnectionParam param = new OracleConnectionParam();
        param.setUser("app");
        param.setSchemaName("app");
        OracleCatalog catalog = catalogWithOwners("oracle_owner_metadata_path", param, "APP", "MiXeD_Owner");

        var fallbackRequest = catalog.preprocessRequest(Map.of(
                "read_mode", "TABLE",
                "table_path", "orders"));
        TablePath fallbackPath = fallbackRequest.getTablePath();
        assertEquals("APP", fallbackPath.getSchemaName());
        assertEquals("\"APP\".\"orders\"", catalog.buildTableReference(fallbackPath));
        assertTrue(catalog.getSelectColumnsSql(fallbackPath).contains("OWNER = 'APP'"));

        var metadataRequest = catalog.preprocessRequest(Map.of(
                "read_mode", "TABLE",
                "table_path", "MiXeD_Owner.alarm_mysql_sync"));
        TablePath metadataPath = metadataRequest.getTablePath();
        assertEquals("\"MiXeD_Owner\".\"alarm_mysql_sync\"", catalog.buildTableReference(metadataPath));
        String metadataSql = catalog.getSelectColumnsSql(metadataPath);
        assertTrue(metadataSql.contains("OWNER = 'MiXeD_Owner'"));
        assertTrue(metadataSql.contains("TABLE_NAME = 'alarm_mysql_sync'"));
    }

    @Test
    void preservesExactLowercaseConfiguredOracleOwnerWhenItExists() throws Exception {
        OracleConnectionParam param = new OracleConnectionParam();
        param.setSchemaName("oracle_app");
        OracleCatalog catalog = catalogWithOwners("oracle_owner_config_exact", param, "ORACLE_APP", "oracle_app");

        var request = catalog.preprocessRequest(Map.of(
                "read_mode", "TABLE",
                "table_path", "alarm_mysql_sync"));

        assertEquals("oracle_app", request.getTablePath().getSchemaName());
        assertEquals("\"oracle_app\".\"alarm_mysql_sync\"", catalog.buildTableReference(request.getTablePath()));
    }

    @Test
    void resolvesNormalizedMetadataSchemaToPhysicalOracleOwner() throws Exception {
        OracleCatalog catalog = catalogWithOwners("oracle_owner_fallback", "ORACLE_APP");

        var request = catalog.preprocessRequest(Map.of(
                "read_mode", "TABLE",
                "table_path", "alarm_mysql_sync",
                "schema_name", "oracle_app"));

        assertEquals("ORACLE_APP", request.getTablePath().getSchemaName());
        assertEquals("\"ORACLE_APP\".\"alarm_mysql_sync\"", catalog.buildTableReference(request.getTablePath()));
        assertTrue(catalog.getSelectColumnsSql(request.getTablePath()).contains("OWNER = 'ORACLE_APP'"));
    }

    @Test
    void keepsExactLowercaseOracleOwnerWhenItExists() throws Exception {
        OracleCatalog catalog = catalogWithOwners("oracle_owner_exact", "ORACLE_APP", "oracle_app");

        var request = catalog.preprocessRequest(Map.of(
                "read_mode", "TABLE",
                "table_path", "alarm_mysql_sync",
                "schema_name", "oracle_app"));

        assertEquals("oracle_app", request.getTablePath().getSchemaName());
        assertEquals("\"oracle_app\".\"alarm_mysql_sync\"", catalog.buildTableReference(request.getTablePath()));
    }

    private OracleCatalog catalogWithOwners(String databaseName, String... owners) throws SQLException {
        return catalogWithOwners(databaseName, new OracleConnectionParam(), owners);
    }

    private OracleCatalog catalogWithOwners(
            String databaseName, OracleConnectionParam param, String... owners) throws SQLException {
        String jdbcUrl = "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ALL_USERS (USERNAME VARCHAR(128))");
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO ALL_USERS VALUES (?)")) {
                for (String owner : owners) {
                    insert.setString(1, owner);
                    insert.executeUpdate();
                }
            }
        }

        return new OracleCatalog(param, new H2ConnectionProvider(jdbcUrl));
    }

    private static final class H2ConnectionProvider implements JdbcConnectionProvider {
        private final String jdbcUrl;

        private H2ConnectionProvider(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        @Override
        public Connection getConnection(ConnectionParam param) {
            try {
                return DriverManager.getConnection(jdbcUrl);
            } catch (SQLException error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public boolean checkDataSourceConnectivity(ConnectionParam connectionParam) {
            return true;
        }
    }
}
