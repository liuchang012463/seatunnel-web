package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetadataConnectorRegistryTest {

    private final MetadataConnectorRegistry registry = new MetadataConnectorRegistry(List.of(
            new MysqlMetadataConnectorAdapter(),
            new PostgresMetadataConnectorAdapter(),
            new DorisMetadataConnectorAdapter()));

    @Test
    void buildsFixed11210MysqlRequestWithExplicitMetadataDefaults() {
        DataSource dataSource = source(7L, DbType.MYSQL,
                "{\"url\":\"jdbc:mysql://db.example:3307/orders\",\"user\":\"reader\",\"password\":\"secret\"}");

        MetadataConnectorAdapter adapter = registry.require(dataSource.getDbType());
        JsonNode service = adapter.databaseServiceRequest(dataSource, "st_ds_7");
        JsonNode pipeline = adapter.metadataPipelineRequest("st_ds_7_metadata", "uuid-1", "st_ds_7");
        JsonNode scheduledPipeline = adapter.metadataPipelineRequest(
                dataSource, "st_ds_7_metadata", "uuid-1", "st_ds_7");

        assertEquals("Mysql", service.at("/connection/config/type").asText());
        assertEquals("db.example:3307", service.at("/connection/config/hostPort").asText());
        assertEquals("orders", service.at("/connection/config/databaseName").asText());
        assertEquals("secret", service.at("/connection/config/authType/password").asText());
        assertEquals(true, pipeline.at("/sourceConfig/config/markDeletedTables").asBoolean());
        assertEquals(true, pipeline.at("/sourceConfig/config/markDeletedSchemas").asBoolean());
        assertEquals(true, pipeline.at("/sourceConfig/config/markDeletedDatabases").asBoolean());
        assertEquals(false, pipeline.at("/sourceConfig/config/includeViews").asBoolean());
        assertEquals(1, pipeline.at("/airflowConfig/maxActiveRuns").asInt());
        assertEquals("7 1 * * *", scheduledPipeline.at("/airflowConfig/scheduleInterval").asText());
    }

    @Test
    void supportsPostgresAndDorisButRejectsDeferredDatabases() {
        assertEquals("Postgres", registry.require(DbType.POSTGRE_SQL).openMetadataServiceType());
        assertEquals("Doris", registry.require(DbType.DORIS).openMetadataServiceType());

        MetadataIntegrationException error = assertThrows(
                MetadataIntegrationException.class, () -> registry.require(DbType.KINGBASE));
        assertEquals(MetadataErrorCode.CONNECTOR_NOT_SUPPORTED, error.getErrorCode());
    }

    @Test
    void profilerUsesAnExactFqnFilterForTheSelectedDatabase() {
        MetadataConnectorAdapter adapter = registry.require(DbType.DORIS);
        JsonNode pipeline = adapter.profilerPipelineRequest(
                "st_ds_7_profiler", "uuid-1", "st_ds_7", "st_ds_7.orders");

        assertEquals(true, pipeline.at("/sourceConfig/config/useFqnForFiltering").asBoolean());
        assertEquals("^st_ds_7\\.orders$",
                pipeline.at("/sourceConfig/config/databaseFilterPattern/includes/0").asText());
    }

    private static DataSource source(Long id, DbType dbType, String connectionParams) {
        DataSource dataSource = new DataSource();
        dataSource.setId(id);
        dataSource.setName("orders");
        dataSource.setDbType(dbType);
        dataSource.setConnectionParams(connectionParams);
        return dataSource;
    }
}
