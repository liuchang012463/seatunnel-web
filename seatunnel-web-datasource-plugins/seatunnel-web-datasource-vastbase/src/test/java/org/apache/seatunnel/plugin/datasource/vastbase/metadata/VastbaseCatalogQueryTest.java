package org.apache.seatunnel.plugin.datasource.vastbase.metadata;

import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.vastbase.param.VastbaseConnectionParam;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VastbaseCatalogQueryTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:vastbase_identifier_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    @Test
    void readsAndCountsMixedCaseTableThroughPostgresStyleQuotedPath() throws Exception {
        prepareMixedCaseTable();

        VastbaseConnectionParam param = new VastbaseConnectionParam();
        param.setDatabase("testdb");
        param.setSchemaName("public");
        VastbaseCatalog catalog = new VastbaseCatalog(param, new H2ConnectionProvider());
        Map<String, Object> request = Map.of(
                "read_mode", "TABLE",
                "table_path", "\"MixedSchema\".\"MixedTable\"");

        var preview = catalog.getTop20Data(request);

        assertEquals("MixedValue", preview.getData().get(0).get("MixedColumn"));
        assertEquals(1, catalog.count(request));
    }

    private void prepareMixedCaseTable() throws SQLException {
        try (Connection connection = DriverManager.getConnection(JDBC_URL);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS \"MixedSchema\"");
            statement.execute("DROP TABLE IF EXISTS \"MixedSchema\".\"MixedTable\"");
            statement.execute(
                    "CREATE TABLE \"MixedSchema\".\"MixedTable\" (\"MixedColumn\" VARCHAR(40))");
            statement.execute(
                    "INSERT INTO \"MixedSchema\".\"MixedTable\" VALUES ('MixedValue')");
        }
    }

    private static final class H2ConnectionProvider implements JdbcConnectionProvider {

        @Override
        public Connection getConnection(ConnectionParam param) {
            try {
                return DriverManager.getConnection(JDBC_URL);
            } catch (SQLException error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public boolean checkDataSourceConnectivity(ConnectionParam connectionParam) {
            return true;
        }
    }
}
