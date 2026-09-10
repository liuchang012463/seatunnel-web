package org.apache.seatunnel.plugin.datasource.kingbase.metadata;

import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.kingbase.param.KingbaseConnectionParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KingbaseCatalogTest {

    @Test
    void listTableSqlReturnsSchemaQualifiedTablePath() {
        KingbaseConnectionParam param = new KingbaseConnectionParam();
        param.setSchemaName("public");
        KingbaseCatalog catalog = new KingbaseCatalog(param, null);

        String sql = catalog.getListTableSql("testdb");

        assertTrue(sql.contains("table_schema || '.' || table_name AS table_path"));
        assertTrue(sql.contains("table_schema NOT IN ('pg_catalog', 'information_schema')"));
        assertTrue(sql.contains("ORDER BY table_schema, table_name"));
    }

    @Test
    void buildTableReferenceUsesSchemaFromTablePath() {
        KingbaseConnectionParam param = new KingbaseConnectionParam();
        param.setSchemaName("public");
        KingbaseCatalog catalog = new KingbaseCatalog(param, null);

        String reference = catalog.buildTableReference(
                TablePath.of("testdb", "app", "orders"));

        assertEquals("app.orders", reference);
    }
}
