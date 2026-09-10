package org.apache.seatunnel.plugin.datasource.api.jdbc;

import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AbstractJdbcCatalogPreprocessRequestTest {

    @Test
    void splitsDottedTablePathIntoSchemaAndTable() {
        BaseConnectionParam param = new BaseConnectionParam() {};
        param.setDatabase("ORCL");
        param.setSchemaName("SYSTEM");
        TestCatalog catalog = new TestCatalog(param);

        Map<String, Object> body = new HashMap<>();
        body.put("read_mode", "TABLE");
        body.put("table_path", "ORACLE_APP.TEST_USER");

        QueryRequest request = catalog.preprocessRequest(body);

        assertEquals("ORCL", request.getTablePath().getDatabaseName());
        assertEquals("ORACLE_APP", request.getTablePath().getSchemaName());
        assertEquals("TEST_USER", request.getTablePath().getTableName());
    }

    @Test
    void keepsBareTablePathAndConnectionSchema() {
        BaseConnectionParam param = new BaseConnectionParam() {};
        param.setDatabase("ORCL");
        param.setSchemaName("ORACLE_APP");
        TestCatalog catalog = new TestCatalog(param);

        Map<String, Object> body = new HashMap<>();
        body.put("read_mode", "TABLE");
        body.put("table_path", "TEST_USER");

        QueryRequest request = catalog.preprocessRequest(body);

        assertEquals("ORCL", request.getTablePath().getDatabaseName());
        assertEquals("ORACLE_APP", request.getTablePath().getSchemaName());
        assertEquals("TEST_USER", request.getTablePath().getTableName());
    }

    @Test
    void prefersExplicitSchemaNameWithBareTablePath() {
        BaseConnectionParam param = new BaseConnectionParam() {};
        param.setDatabase("ORCL");
        param.setSchemaName("SYSTEM");
        TestCatalog catalog = new TestCatalog(param);

        Map<String, Object> body = new HashMap<>();
        body.put("read_mode", "TABLE");
        body.put("table_path", "TEST_USER");
        body.put("schema_name", "ORACLE_APP");

        QueryRequest request = catalog.preprocessRequest(body);

        assertEquals("ORACLE_APP", request.getTablePath().getSchemaName());
        assertEquals("TEST_USER", request.getTablePath().getTableName());
        assertNull(request.getQuery());
    }

    private static final class TestCatalog extends AbstractJdbcCatalog {
        private TestCatalog(BaseConnectionParam param) {
            super(param, null);
        }
    }
}
