package org.apache.seatunnel.plugin.datasource.pgsql.metadata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PgSQLCatalogTest {

    @Test
    void listTableSqlReturnsSchemaQualifiedTablePath() {
        PgSQLCatalog catalog = new PgSQLCatalog(null, null);

        String sql = catalog.getListTableSql("orders");

        assertTrue(sql.contains("table_schema || '.' || table_name AS table_path"));
        assertTrue(sql.contains("table_schema NOT IN ('pg_catalog', 'information_schema')"));
    }
}
