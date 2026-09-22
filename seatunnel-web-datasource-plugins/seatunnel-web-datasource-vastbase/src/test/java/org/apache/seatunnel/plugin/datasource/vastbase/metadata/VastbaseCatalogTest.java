package org.apache.seatunnel.plugin.datasource.vastbase.metadata;

import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.vastbase.param.VastbaseConnectionParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VastbaseCatalogTest {

    @Test
    void listTableSqlUsesInformationSchema() {
        VastbaseConnectionParam param = new VastbaseConnectionParam();
        param.setSchemaName("public");
        VastbaseCatalog catalog = new VastbaseCatalog(param, null);

        String sql = catalog.getListTableSql("postgres");

        assertTrue(sql.contains("table_schema || '.' || table_name AS table_path"));
        assertTrue(sql.contains("table_schema NOT IN ('pg_catalog', 'information_schema')"));
    }

    @Test
    void tableReferenceUsesSchemaFromPath() {
        VastbaseConnectionParam param = new VastbaseConnectionParam();
        param.setSchemaName("public");
        VastbaseCatalog catalog = new VastbaseCatalog(param, null);

        assertEquals("app.orders", catalog.buildTableReference(
                TablePath.of("postgres", "app", "orders")));
    }
}
