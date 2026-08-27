package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataConnectorRegistryTest {

    private final MetadataConnectorRegistry registry = new MetadataConnectorRegistry(List.of(
            new MysqlMetadataConnectorAdapter(),
            new PostgresMetadataConnectorAdapter(),
            new DorisMetadataConnectorAdapter(),
            new OracleMetadataConnectorAdapter(),
            new DamengMetadataConnectorAdapter(),
            new KingbaseMetadataConnectorAdapter(),
            new JdbcMetadataConnectorAdapter()));

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
        assertEquals(false, scheduledPipeline.at("/airflowConfig/pausePipeline").asBoolean());
        assertEquals(true, scheduledPipeline.at("/airflowConfig/scheduleInterval").isNull());
    }

    @Test
    void supportsPostgresDorisAndCustomDatabaseExtensions() {
        assertEquals("Postgres", registry.require(DbType.POSTGRE_SQL).openMetadataServiceType());
        assertEquals("Doris", registry.require(DbType.DORIS).openMetadataServiceType());
        assertEquals("CustomDatabase", registry.require(DbType.DAMENG).openMetadataServiceType());
        assertEquals("CustomDatabase", registry.require(DbType.KINGBASE).openMetadataServiceType());
    }

    @Test
    void buildsFixed11210OracleServiceNameRequest() {
        DataSource dataSource = source(8L, DbType.ORACLE,
                "{\"url\":\"jdbc:oracle:thin:@//oracle.example:1521/FREEPDB1\","
                        + "\"user\":\"oracle_app\",\"password\":\"secret\","
                        + "\"connectType\":\"ORACLE_SERVICE_NAME\"}");

        JsonNode service = registry.require(DbType.ORACLE)
                .databaseServiceRequest(dataSource, "st_ds_8");

        assertEquals("Oracle", service.at("/connection/config/type").asText());
        assertEquals("oracle+cx_oracle", service.at("/connection/config/scheme").asText());
        assertEquals("oracle.example:1521", service.at("/connection/config/hostPort").asText());
        assertEquals("FREEPDB1", service.at("/connection/config/oracleConnectionType/oracleServiceName").asText());
        assertEquals("/instantclient", service.at("/connection/config/instantClientDirectory").asText());
        assertEquals(true, service.at("/connection/config/supportsProfiler").asBoolean());
    }

    @Test
    void convertsHistoricalPostgresJdbcRow() {
        DataSource dataSource = source(9L, DbType.JDBC,
                "{\"url\":\"jdbc:postgresql://db.example:5432/orders\","
                        + "\"user\":\"reader\",\"password\":\"secret\"}");
        JsonNode service = registry.require(DbType.JDBC)
                .databaseServiceRequest(dataSource, "st_ds_9");
        assertEquals("Postgres", service.at("/serviceType").asText());
        assertEquals("orders", service.at("/connection/config/database").asText());
    }

    @Test
    void emitsVerifiedCustomDatabaseConnectionForKingbase() {
        DataSource dataSource = source(10L, DbType.KINGBASE,
                "{\"url\":\"jdbc:kingbase8://192.168.100.91:54321/kingbase\","
                        + "\"user\":\"system\",\"password\":\"secret\",\"schemaName\":\"PUBLIC\"}");

        JsonNode service = registry.require(DbType.KINGBASE)
                .databaseServiceRequest(dataSource, "st_ds_10");

        assertEquals("CustomDatabase", service.at("/serviceType").asText());
        assertEquals("CustomDatabase", service.at("/connection/config/type").asText());
        assertEquals("kingbase_connector.kingbase_source.KingbaseSource",
                service.at("/connection/config/sourcePythonClass").asText());
        assertEquals("192.168.100.91:54321",
                service.at("/connection/config/connectionOptions/hostPort").asText());
        assertEquals("kingbase",
                service.at("/connection/config/connectionOptions/database").asText());
        assertEquals("PUBLIC",
                service.at("/connection/config/connectionOptions/schema").asText());
    }

    @Test
    void emitsVerifiedCustomDatabaseConnectionForDameng() {
        DataSource dataSource = source(11L, DbType.DAMENG,
                "{\"url\":\"jdbc:dm://dm.example:5236\","
                        + "\"user\":\"SYSDBA\",\"password\":\"secret\","
                        + "\"database\":\"DAMENG\",\"schemaName\":\"TEST\"}");

        JsonNode service = registry.require(DbType.DAMENG)
                .databaseServiceRequest(dataSource, "st_ds_11");

        assertEquals("dameng_connector.dameng_source.DamengSource",
                service.at("/connection/config/sourcePythonClass").asText());
        assertEquals("dm.example:5236",
                service.at("/connection/config/connectionOptions/hostPort").asText());
        assertTrue(service.at("/connection/config/connectionOptions/database").isMissingNode());
        assertEquals("TEST",
                service.at("/connection/config/connectionOptions/schema").asText());
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
