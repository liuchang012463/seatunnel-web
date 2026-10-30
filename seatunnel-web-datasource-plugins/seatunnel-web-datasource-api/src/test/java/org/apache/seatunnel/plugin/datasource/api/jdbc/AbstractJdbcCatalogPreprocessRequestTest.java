package org.apache.seatunnel.plugin.datasource.api.jdbc;

import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
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

    @Test
    void prefersExplicitDatabaseAndSchemaWithQualifiedTablePath() {
        BaseConnectionParam param = new BaseConnectionParam() {};
        param.setDatabase("DEFAULT_DB");
        param.setSchemaName("DEFAULT_SCHEMA");
        TestCatalog catalog = new TestCatalog(param);

        Map<String, Object> body = new HashMap<>();
        body.put("read_mode", "TABLE");
        body.put("table_path", "OLD_SCHEMA.TEST_USER");
        body.put("database", "SELECTED_DB");
        body.put("schema_name", "SELECTED_SCHEMA");

        QueryRequest request = catalog.preprocessRequest(body);

        assertEquals("SELECTED_DB", request.getTablePath().getDatabaseName());
        assertEquals("SELECTED_SCHEMA", request.getTablePath().getSchemaName());
        assertEquals("TEST_USER", request.getTablePath().getTableName());
    }

    @Test
    void parsesQuotedPathComponentsWithoutSplittingDotsInsideIdentifiers() {
        BaseConnectionParam param = new BaseConnectionParam() {};
        param.setDatabase("ORCL");
        TestCatalog catalog = new TestCatalog(param);

        Map<String, Object> body = new HashMap<>();
        body.put("read_mode", "TABLE");
        body.put("table_path", "\"Sales.Schema\".\"Select\"\"ed\"");

        QueryRequest request = catalog.preprocessRequest(body);

        assertEquals("ORCL", request.getTablePath().getDatabaseName());
        assertEquals("Sales.Schema", request.getTablePath().getSchemaName());
        assertEquals("Select\"ed", request.getTablePath().getTableName());
    }

    @Test
    void keepsAQuotedDottedTableAsOneIdentifier() {
        BaseConnectionParam param = new BaseConnectionParam() {};
        param.setDatabase("ORCL");
        param.setSchemaName("APP");
        TestCatalog catalog = new TestCatalog(param);

        Map<String, Object> body = new HashMap<>();
        body.put("read_mode", "TABLE");
        body.put("table_path", "`Archive.2026`");

        QueryRequest request = catalog.preprocessRequest(body);

        assertEquals("APP", request.getTablePath().getSchemaName());
        assertEquals("Archive.2026", request.getTablePath().getTableName());
        assertEquals(List.of("Archive.2026"), catalog.parse("`Archive.2026`"));
    }

    @Test
    void quotesAnsiIdentifiersAndEscapesEmbeddedQuotes() {
        TestCatalog catalog = new TestCatalog(new BaseConnectionParam() {});

        assertEquals("\"Select\"", catalog.quote("Select"));
        assertEquals("\"Order\"\"Details\"", catalog.quote("Order\"Details"));
    }

    private static final class TestCatalog extends AbstractJdbcCatalog {
        private TestCatalog(BaseConnectionParam param) {
            super(param, null);
        }

        private List<String> parse(String value) {
            return splitIdentifierPath(value);
        }

        private String quote(String value) {
            return quoteIdentifier(value);
        }
    }
}
