package org.apache.seatunnel.plugin.datasource.doris.metadata;

import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.doris.param.DorisConnectionParam;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DorisCatalogTest {

    @Test
    void treatsTwoPartTablePathsAsDatabaseAndTable() {
        DorisConnectionParam param = new DorisConnectionParam();
        param.setDatabase("ods");
        ExposedDorisCatalog catalog = new ExposedDorisCatalog(param);

        TablePath path = catalog.resolve("analytics.orders");

        assertEquals("analytics", path.getDatabaseName());
        assertNull(path.getSchemaName());
        assertEquals("orders", path.getTableName());
        assertEquals("`analytics`.`orders`", catalog.reference(path));
    }

    @Test
    void explicitDatabaseOverridesTheConnectionDatabaseForPreviewRequests() {
        DorisConnectionParam param = new DorisConnectionParam();
        param.setDatabase("ods");
        ExposedDorisCatalog catalog = new ExposedDorisCatalog(param);

        var request = catalog.preprocess(java.util.Map.of(
                "read_mode", "table",
                "table_path", "orders",
                "database", "analytics"));

        assertEquals("analytics", request.getTablePath().getDatabaseName());
        assertEquals("orders", request.getTablePath().getTableName());
    }

    @Test
    void firstColumnSqlDoesNotRequireNonNullableColumns() {
        String sql = DorisCatalog.firstColumnSql("ods", "test");

        assertFalse(sql.contains("IS_NULLABLE"), sql);
        assertTrue(sql.contains("TABLE_SCHEMA = 'ods'"), sql);
        assertTrue(sql.contains("TABLE_NAME = 'test'"), sql);
        assertTrue(sql.contains("ORDER BY ORDINAL_POSITION ASC LIMIT 1"), sql);
    }

    private static final class ExposedDorisCatalog extends DorisCatalog {

        private ExposedDorisCatalog(DorisConnectionParam param) {
            super(param, new NoopConnectionProvider());
        }

        private TablePath resolve(String tablePath) {
            return resolveTablePath(tablePath);
        }

        private String reference(TablePath tablePath) {
            return buildTableReference(tablePath);
        }

        private org.apache.seatunnel.plugin.datasource.api.jdbc.QueryRequest preprocess(
                java.util.Map<String, Object> requestBody) {
            return preprocessRequest(requestBody);
        }
    }

    private static final class NoopConnectionProvider implements JdbcConnectionProvider {

        @Override
        public Connection getConnection(ConnectionParam param) {
            throw new UnsupportedOperationException("connection is not needed for this test");
        }

        @Override
        public boolean checkDataSourceConnectivity(ConnectionParam connectionParam) {
            return false;
        }
    }
}
