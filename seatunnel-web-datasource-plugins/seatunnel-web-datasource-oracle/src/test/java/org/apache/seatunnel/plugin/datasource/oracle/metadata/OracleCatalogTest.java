package org.apache.seatunnel.plugin.datasource.oracle.metadata;

import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.oracle.param.OracleConnectionParam;
import org.junit.jupiter.api.Test;

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
    void foldsConfiguredSchemaFallbackButPreservesQualifiedMetadataNames() {
        OracleConnectionParam param = new OracleConnectionParam();
        param.setUser("app");
        param.setSchemaName("app");
        OracleCatalog catalog = new OracleCatalog(param, null);

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
}
