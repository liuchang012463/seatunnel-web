package org.apache.seatunnel.plugin.datasource.dameng.metadata;

import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.dameng.param.DamengConnectionParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamengCatalogTest {

    @Test
    void listTableSqlReturnsSchemaQualifiedTablePath() {
        DamengConnectionParam param = new DamengConnectionParam();
        param.setSchemaName("SYSDBA");
        DamengCatalog catalog = new DamengCatalog(param, null);

        String sql = catalog.getListTableSql("testdb");

        assertTrue(sql.contains("table_schema || '.' || table_name AS table_path"));
        assertTrue(sql.contains("table_schema NOT IN ('INFORMATION_SCHEMA', 'SYS', 'CTISYS')"));
        assertTrue(sql.contains("ORDER BY table_schema, table_name"));
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
