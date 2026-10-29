package org.apache.seatunnel.plugin.datasource.dameng.metadata;

import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.dameng.param.DamengConnectionParam;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamengCatalogTest {

    @Test
    void listTableSqlUsesAllTablesInsteadOfInformationSchema() {
        DamengConnectionParam param = new DamengConnectionParam();
        param.setSchemaName("SYSDBA");
        DamengCatalog catalog = new DamengCatalog(param, null);

        String sql = catalog.getListTableSql("testdb");

        assertTrue(sql.contains("OWNER || '.' || TABLE_NAME AS table_path"));
        assertTrue(sql.contains("FROM ALL_TABLES"));
        assertTrue(sql.contains("OWNER NOT IN ('SYS', 'SYSAUDITOR', 'SYSSSO', 'CTISYS')"));
        assertTrue(sql.contains("ORDER BY OWNER, TABLE_NAME"));
        assertFalse(sql.toLowerCase().contains("information_schema"));
    }

    @Test
    void selectColumnsSqlUsesAllTabColumns() {
        DamengConnectionParam param = new DamengConnectionParam();
        param.setSchemaName("SYSDBA");
        DamengCatalog catalog = new DamengCatalog(param, null);

        String sql = catalog.getSelectColumnsSql(TablePath.of("testdb", "APP", "orders"));

        assertTrue(sql.contains("FROM ALL_TAB_COLUMNS"));
        assertTrue(sql.contains("OWNER = 'APP'"));
        assertTrue(sql.contains("TABLE_NAME = 'orders'"));
        assertFalse(sql.toLowerCase().contains("information_schema"));
    }

    @Test
    void buildTableReferenceUsesSchemaFromTablePath() {
        DamengConnectionParam param = new DamengConnectionParam();
        param.setSchemaName("SYSDBA");
        DamengCatalog catalog = new DamengCatalog(param, null);

        String reference = catalog.buildTableReference(
                TablePath.of("testdb", "APP", "ORDERS"));

        assertEquals("\"APP\".\"ORDERS\"", reference);
    }

    @Test
    void preservesMixedCaseMetadataNamesAndQuotesEmbeddedQuotes() {
        DamengConnectionParam param = new DamengConnectionParam();
        param.setSchemaName("SYSDBA");
        DamengCatalog catalog = new DamengCatalog(param, null);

        assertEquals("\"MixedOwner\".\"Order\"\"Details\"", catalog.buildTableReference(
                TablePath.of("testdb", "MixedOwner", "Order\"Details")));

        String sql = catalog.getSelectColumnsSql(
                TablePath.of("testdb", "MixedOwner", "OrderDetails"));
        assertTrue(sql.contains("OWNER = 'MixedOwner'"));
        assertTrue(sql.contains("TABLE_NAME = 'OrderDetails'"));
    }

    @Test
    void foldsConfiguredSchemaFallbackButPreservesQualifiedMetadataNames() {
        DamengConnectionParam param = new DamengConnectionParam();
        param.setSchemaName("app");
        DamengCatalog catalog = new DamengCatalog(param, null);

        var fallbackRequest = catalog.preprocessRequest(Map.of(
                "read_mode", "TABLE",
                "table_path", "orders"));
        TablePath fallbackPath = fallbackRequest.getTablePath();
        assertEquals("APP", fallbackPath.getSchemaName());
        assertEquals("ORDERS", fallbackPath.getTableName());
        assertEquals("\"APP\".\"ORDERS\"", catalog.buildTableReference(fallbackPath));
        String fallbackSql = catalog.getSelectColumnsSql(fallbackPath);
        assertTrue(fallbackSql.contains("OWNER = 'APP'"));
        assertTrue(fallbackSql.contains("TABLE_NAME = 'ORDERS'"));

        var quotedFallbackRequest = catalog.preprocessRequest(Map.of(
                "read_mode", "TABLE",
                "table_path", "\"orders\""));
        TablePath quotedFallbackPath = quotedFallbackRequest.getTablePath();
        assertEquals("APP", quotedFallbackPath.getSchemaName());
        assertEquals("orders", quotedFallbackPath.getTableName());
        assertEquals("\"APP\".\"orders\"", catalog.buildTableReference(quotedFallbackPath));

        var metadataRequest = catalog.preprocessRequest(Map.of(
                "read_mode", "TABLE",
                "table_path", "MiXeD_Owner.alarm_mysql_sync"));
        TablePath metadataPath = metadataRequest.getTablePath();
        assertEquals("\"MiXeD_Owner\".\"alarm_mysql_sync\"", catalog.buildTableReference(metadataPath));
        String metadataSql = catalog.getSelectColumnsSql(metadataPath);
        assertTrue(metadataSql.contains("OWNER = 'MiXeD_Owner'"));
        assertTrue(metadataSql.contains("TABLE_NAME = 'alarm_mysql_sync'"));

        var explicitSchemaRequest = catalog.preprocessRequest(Map.of(
                "read_mode", "TABLE",
                "table_path", "alarm_mysql_sync",
                "schema_name", "MiXeD_Owner"));
        assertEquals("MiXeD_Owner", explicitSchemaRequest.getTablePath().getSchemaName());
        assertEquals("alarm_mysql_sync", explicitSchemaRequest.getTablePath().getTableName());
    }
}
