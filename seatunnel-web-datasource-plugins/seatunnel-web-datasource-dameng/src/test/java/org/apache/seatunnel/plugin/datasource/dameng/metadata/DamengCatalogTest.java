package org.apache.seatunnel.plugin.datasource.dameng.metadata;

import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.dameng.param.DamengConnectionParam;
import org.junit.jupiter.api.Test;

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
        assertTrue(sql.contains("TABLE_NAME = 'ORDERS'"));
        assertFalse(sql.toLowerCase().contains("information_schema"));
    }

    @Test
    void buildTableReferenceUsesSchemaFromTablePath() {
        DamengConnectionParam param = new DamengConnectionParam();
        param.setSchemaName("SYSDBA");
        DamengCatalog catalog = new DamengCatalog(param, null);

        String reference = catalog.buildTableReference(
                TablePath.of("testdb", "APP", "ORDERS"));

        assertEquals("APP.ORDERS", reference);
    }
}
