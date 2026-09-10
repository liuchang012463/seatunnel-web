package org.apache.seatunnel.plugin.datasource.oracle.metadata;

import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.oracle.param.OracleConnectionParam;
import org.junit.jupiter.api.Test;

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
}
