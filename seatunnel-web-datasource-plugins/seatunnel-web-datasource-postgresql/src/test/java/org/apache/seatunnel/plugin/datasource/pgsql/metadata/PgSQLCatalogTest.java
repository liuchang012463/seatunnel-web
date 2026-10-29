package org.apache.seatunnel.plugin.datasource.pgsql.metadata;

import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.pgsql.param.PgSQLConnectionParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgSQLCatalogTest {

    @Test
    void listTableSqlReturnsSchemaQualifiedTablePath() {
        PgSQLCatalog catalog = new PgSQLCatalog(null, null);

        String sql = catalog.getListTableSql("orders");

        assertTrue(sql.contains("table_schema || '.' || table_name AS table_path"));
        assertTrue(sql.contains("table_schema NOT IN ('pg_catalog', 'information_schema')"));
    }

    @Test
    void quotesMixedCaseAndReservedIdentifiersInTableReference() {
        PgSQLConnectionParam param = new PgSQLConnectionParam();
        param.setSchemaName("public");
        PgSQLCatalog catalog = new PgSQLCatalog(param, null);

        assertEquals("\"Sales\".\"Select\"", catalog.buildTableReference(
                TablePath.of("postgres", "Sales", "Select")));
        assertEquals("\"Sales\".\"Order\"\"Details\"", catalog.buildTableReference(
                TablePath.of("postgres", "Sales", "Order\"Details")));
    }
}
