package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataColumn;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabase;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTable;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenMetadataJobLineageServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private OpenMetadataClient openMetadataClient;

    @Mock
    private MetadataBindingDao metadataBindingDao;

    @Mock
    private JobInstanceDao jobInstanceDao;

    @Mock
    private StreamingJobInstanceDao streamingJobInstanceDao;

    @InjectMocks
    private OpenMetadataJobLineageService service;

    /**
     * The resolver probes candidate fully qualified names until one is registered, so the
     * default answer for every lookup is "not registered".
     */
    @BeforeEach
    void defaultLookupsToUnregistered() {
        lenient().when(openMetadataClient.getTableByName(anyString())).thenReturn(null);
        lenient().when(openMetadataClient.listDatabases(anyString(), anyInt())).thenReturn(List.of());
    }

    /**
     * A MySQL source stores {@code database=seatunnel_web} and
     * {@code table_path=seatunnel_web.orders}, so OpenMetadata keeps the connected database as
     * both the database and the schema level.
     */
    @Test
    void resolvesMysqlSourceAndPostgresSinkFqnsAndWritesManyToOneLineage() throws Exception {
        JobInstanceVO instance = instance();
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(instance);
        when(metadataBindingDao.queryByDataSourceId(10L)).thenReturn(binding("st_ds_22964041928224"));
        when(metadataBindingDao.queryByDataSourceId(20L)).thenReturn(binding("st_ds_22978064259872"));

        OpenMetadataTable source = table(
                "source-table-id",
                "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel",
                "name", "channel_type", "description");
        OpenMetadataTable target = table(
                "target-table-id",
                "st_ds_22978064259872.test.public.test",
                "name", "category");
        when(openMetadataClient.getTableByName(
                "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel"))
                .thenReturn(source);
        when(openMetadataClient.getTableByName("st_ds_22978064259872.test.public.test"))
                .thenReturn(target);

        service.recordBatchSubmission(instance, "engine-job-42");

        ArgumentCaptor<JsonNode> requestCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(openMetadataClient).addLineage(requestCaptor.capture());
        JsonNode edge = requestCaptor.getValue().path("edge");
        assertEquals("source-table-id", edge.path("fromEntity").path("id").asText());
        assertEquals("target-table-id", edge.path("toEntity").path("id").asText());

        JsonNode columnLineage = edge.path("lineageDetails").path("columnsLineage").get(0);
        assertNotNull(columnLineage);
        assertEquals("st_ds_22978064259872.test.public.test.category",
                columnLineage.path("toColumn").asText());
        assertEquals(List.of(
                        "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel.channel_type",
                        "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel.description"),
                OBJECT_MAPPER.convertValue(columnLineage.path("fromColumns"),
                        OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));

        JsonNode description = OBJECT_MAPPER.readTree(
                edge.path("lineageDetails").path("description").asText());
        assertEquals("BATCH", description.path("job").path("kind").asText());
        assertEquals("200", description.path("job").path("definitionId").asText());
        assertEquals("Customer summary", description.path("job").path("name").asText());
        assertEquals("100", description.path("run").path("instanceId").asText());
        assertEquals("engine-job-42", description.path("run").path("engineJobId").asText());
    }

    /**
     * A Vastbase/KingBase service is ingested under an OpenMetadata database named
     * {@code default} although the connector connects to {@code vastbase}: only the databases
     * reported by OpenMetadata lead to the registered table.
     */
    @Test
    void fallsBackToTheDatabasesReportedByOpenMetadata() {
        JobInstanceVO instance = instance();
        instance.setSourceDatasourceId(30L);
        instance.setSourceTable("test_sync_20260923");
        instance.setSinkDatasourceId(31L);
        instance.setSinkTable("test");
        instance.setRuntimeConfig("""
                source {
                    Jdbc {
                        database=vastbase
                        table_path="vastbase.public.test_sync_20260923"
                        url="jdbc:postgresql://192.168.100.95:25432/vastbase?ssl=false"
                    }
                }
                transform = [{
                  FieldMapper {
                    plugin_input = source
                    plugin_output = sink
                    field_mapper {
                      name = name
                    }
                  }
                }]
                sink {
                    Jdbc {
                        database=vastbase
                        table="public.test"
                    }
                }
                """);
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(instance);
        when(metadataBindingDao.queryByDataSourceId(30L)).thenReturn(binding("st_ds_23132197819872"));
        when(metadataBindingDao.queryByDataSourceId(31L)).thenReturn(binding("st_ds_23132197819872"));
        when(openMetadataClient.listDatabases(eq("st_ds_23132197819872"), anyInt())).thenReturn(List.of(
                new OpenMetadataDatabase("db-id", "st_ds_23132197819872.default", "st_ds_23132197819872")));

        OpenMetadataTable source = table("source-table-id",
                "st_ds_23132197819872.default.public.test_sync_20260923", "name");
        OpenMetadataTable target = table("target-table-id",
                "st_ds_23132197819872.default.public.test", "name");
        when(openMetadataClient.getTableByName("st_ds_23132197819872.default.public.test_sync_20260923"))
                .thenReturn(source);
        when(openMetadataClient.getTableByName("st_ds_23132197819872.default.public.test"))
                .thenReturn(target);

        service.recordBatchSubmission(instance, "engine-job-42");

        verify(openMetadataClient).addLineage(any(JsonNode.class));
    }

    /** A MySQL CDC source carries its table in {@code table-names} and its database in the url. */
    @Test
    void resolvesStreamingCdcTablesAndWritesLineage() throws Exception {
        JobInstanceVO instance = instance();
        instance.setJobType("STREAMING");
        instance.setRunMode("MANUAL");
        instance.setRuntimeConfig("""
                source {
                    MySQL-CDC {
                        base-url="jdbc:mysql://192.168.100.95:33306"
                        "table-names"=["seatunnel_web.t_seatunnel_web_alarm_channel"]
                    }
                }
                transform = [{
                  FieldMapper {
                    plugin_input = source
                    plugin_output = sink
                    field_mapper {
                      channel_type = category
                    }
                  }
                }]
                sink {
                    Jdbc {
                        database="seatunnel_web"
                        table="st3_cdc_sink"
                    }
                }
                """);
        when(streamingJobInstanceDao.selectDetailById(100L)).thenReturn(instance);
        when(metadataBindingDao.queryByDataSourceId(10L)).thenReturn(binding("st_ds_22964041928224"));
        when(metadataBindingDao.queryByDataSourceId(20L)).thenReturn(binding("st_ds_22964041928224"));
        when(openMetadataClient.listDatabases(eq("st_ds_22964041928224"), anyInt())).thenReturn(List.of(
                new OpenMetadataDatabase("db-id", "st_ds_22964041928224.seatunnel_web",
                        "st_ds_22964041928224")));

        OpenMetadataTable source = table("source-table-id",
                "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel",
                "channel_type");
        OpenMetadataTable target = table("target-table-id",
                "st_ds_22964041928224.seatunnel_web.seatunnel_web.st3_cdc_sink", "category");
        when(openMetadataClient.getTableByName(
                "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel"))
                .thenReturn(source);
        when(openMetadataClient.getTableByName(
                "st_ds_22964041928224.seatunnel_web.seatunnel_web.st3_cdc_sink"))
                .thenReturn(target);

        service.recordStreamingSubmission(instance, "engine-job-43");

        ArgumentCaptor<JsonNode> requestCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(openMetadataClient).addLineage(requestCaptor.capture());
        JsonNode description = OBJECT_MAPPER.readTree(requestCaptor.getValue()
                .path("edge").path("lineageDetails").path("description").asText());
        assertEquals("STREAMING", description.path("job").path("kind").asText());
    }

    /**
     * A many-to-one mapping cannot run as a FieldMapper, so it is submitted as a Sql transform and
     * the projection of that query is the mapping.
     */
    @Test
    void readsManyToOneMappingsFromTheGeneratedSqlTransform() throws Exception {
        JobInstanceVO instance = instance();
        instance.setRuntimeConfig("""
                source {
                    Jdbc {
                        database="seatunnel_web"
                        table_path="seatunnel_web.t_seatunnel_web_alarm_channel"
                        url="jdbc:mysql://192.168.100.95:33306/seatunnel_web?useSSL=false"
                    }
                }
                transform {
                    Sql {
                        "plugin_input"=source-1
                        "plugin_output"=sink-1
                        query="SELECT COALESCE(`channel_type`, `description`) AS `province`, `name` AS `user_name` FROM `source-1`"
                    }
                }
                sink {
                    Jdbc {
                        database=test
                        table="public.dwd_user"
                    }
                }
                """);
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(instance);
        when(metadataBindingDao.queryByDataSourceId(10L)).thenReturn(binding("st_ds_22964041928224"));
        when(metadataBindingDao.queryByDataSourceId(20L)).thenReturn(binding("st_ds_22978064259872"));

        OpenMetadataTable source = table("source-table-id",
                "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel",
                // A column named like the SQL function must not be mistaken for a source column.
                "name", "channel_type", "description", "COALESCE");
        OpenMetadataTable target = table("target-table-id",
                "st_ds_22978064259872.test.public.dwd_user", "user_name", "province");
        when(openMetadataClient.getTableByName(
                "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel"))
                .thenReturn(source);
        when(openMetadataClient.getTableByName("st_ds_22978064259872.test.public.dwd_user"))
                .thenReturn(target);

        service.recordBatchSubmission(instance, "engine-job-42");

        ArgumentCaptor<JsonNode> requestCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(openMetadataClient).addLineage(requestCaptor.capture());
        JsonNode columnsLineage = requestCaptor.getValue()
                .path("edge").path("lineageDetails").path("columnsLineage");
        JsonNode province = columnsLineage.get(0);
        assertEquals("st_ds_22978064259872.test.public.dwd_user.province",
                province.path("toColumn").asText());
        assertEquals(List.of(
                        "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel.channel_type",
                        "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel.description"),
                OBJECT_MAPPER.convertValue(province.path("fromColumns"),
                        OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
    }

    /** A read query on the source connector says nothing about the target table. */
    @Test
    void ignoresAQueryConfiguredOnTheSourceConnector() {
        JobInstanceVO instance = instance();
        instance.setRuntimeConfig("""
                source {
                    Jdbc {
                        readMode="sql"
                        query="SELECT channel_type FROM seatunnel_web.t_seatunnel_web_alarm_channel"
                        url="jdbc:mysql://192.168.100.95:33306/seatunnel_web?useSSL=false"
                    }
                }
                transform {}
                sink {
                    Jdbc {
                        database=test
                        table="public.dwd_user"
                    }
                }
                """);
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(instance);

        service.recordBatchSubmission(instance, "engine-job-42");

        verify(openMetadataClient, never()).getTableByName(anyString());
        verify(openMetadataClient, never()).addLineage(any(JsonNode.class));
    }

    @Test
    void skipsLineageWhenOpenMetadataHasNotRegisteredTheTables() {
        JobInstanceVO instance = instance();
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(instance);
        when(metadataBindingDao.queryByDataSourceId(10L)).thenReturn(binding("st_ds_22964041928224"));
        when(metadataBindingDao.queryByDataSourceId(20L)).thenReturn(binding("st_ds_22978064259872"));

        service.recordBatchSubmission(instance, "engine-job-42");

        verify(openMetadataClient, never()).addLineage(any(JsonNode.class));
    }

    @Test
    void skipsLineageForAJobWithoutFieldMappings() {
        JobInstanceVO instance = instance();
        instance.setRuntimeConfig("""
                source { Jdbc { table_path="seatunnel_web.orders" } }
                transform {}
                sink { Jdbc { table="public.orders" } }
                """);
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(instance);

        service.recordBatchSubmission(instance, "engine-job-42");

        verify(openMetadataClient, never()).getTableByName(anyString());
        verify(openMetadataClient, never()).addLineage(any(JsonNode.class));
    }

    @Test
    void extractsLineageFromCaseWhenExpressionAndFiltersSqlKeywords() throws Exception {
        JobInstanceVO instance = instance();
        instance.setRuntimeConfig("""
                source {
                    Jdbc {
                        database="seatunnel_web"
                        table_path="seatunnel_web.t_seatunnel_web_alarm_channel"
                        url="jdbc:mysql://192.168.100.95:33306/seatunnel_web?useSSL=false"
                    }
                }
                transform {
                    Sql {
                        "plugin_input"=source-1
                        "plugin_output"=sink-1
                        query="SELECT CASE WHEN s.channel_type IS NOT NULL THEN s.description ELSE 'default' END AS `category` FROM `source-1` AS s"
                    }
                }
                sink {
                    Jdbc {
                        database=test
                        table="public.dwd_user"
                    }
                }
                """);
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(instance);
        when(metadataBindingDao.queryByDataSourceId(10L)).thenReturn(binding("st_ds_22964041928224"));
        when(metadataBindingDao.queryByDataSourceId(20L)).thenReturn(binding("st_ds_22978064259872"));

        OpenMetadataTable source = table("source-table-id",
                "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel",
                "channel_type", "description", "CASE", "WHEN", "THEN", "ELSE", "END", "IS", "NOT", "NULL");
        OpenMetadataTable target = table("target-table-id",
                "st_ds_22978064259872.test.public.dwd_user", "category");
        when(openMetadataClient.getTableByName(
                "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel"))
                .thenReturn(source);
        when(openMetadataClient.getTableByName("st_ds_22978064259872.test.public.dwd_user"))
                .thenReturn(target);

        service.recordBatchSubmission(instance, "engine-job-42");

        ArgumentCaptor<JsonNode> requestCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(openMetadataClient).addLineage(requestCaptor.capture());
        JsonNode columnsLineage = requestCaptor.getValue()
                .path("edge").path("lineageDetails").path("columnsLineage");
        assertEquals(1, columnsLineage.size());
        JsonNode category = columnsLineage.get(0);
        assertEquals("st_ds_22978064259872.test.public.dwd_user.category",
                category.path("toColumn").asText());
        assertEquals(List.of(
                        "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel.channel_type",
                        "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel.description"),
                OBJECT_MAPPER.convertValue(category.path("fromColumns"),
                        OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
    }

    @Test
    void resolvesLegacyTableNamesOptionAndWritesLineage() throws Exception {
        JobInstanceVO instance = instance();
        instance.setJobType("STREAMING");
        instance.setRunMode("MANUAL");
        instance.setRuntimeConfig("""
                source {
                    MySQL-CDC {
                        base-url="jdbc:mysql://192.168.100.95:33306"
                        table_names=["seatunnel_web.t_seatunnel_web_alarm_channel"]
                    }
                }
                transform = [{
                  FieldMapper {
                    plugin_input = source
                    plugin_output = sink
                    field_mapper {
                      channel_type = category
                    }
                  }
                }]
                sink {
                    Jdbc {
                        database="seatunnel_web"
                        table="st3_cdc_sink"
                    }
                }
                """);
        when(streamingJobInstanceDao.selectDetailById(100L)).thenReturn(instance);
        when(metadataBindingDao.queryByDataSourceId(10L)).thenReturn(binding("st_ds_22964041928224"));
        when(metadataBindingDao.queryByDataSourceId(20L)).thenReturn(binding("st_ds_22964041928224"));
        when(openMetadataClient.listDatabases(eq("st_ds_22964041928224"), anyInt())).thenReturn(List.of(
                new OpenMetadataDatabase("db-id", "st_ds_22964041928224.seatunnel_web",
                        "st_ds_22964041928224")));

        OpenMetadataTable source = table("source-table-id",
                "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel",
                "channel_type");
        OpenMetadataTable target = table("target-table-id",
                "st_ds_22964041928224.seatunnel_web.seatunnel_web.st3_cdc_sink", "category");
        when(openMetadataClient.getTableByName(
                "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel"))
                .thenReturn(source);
        when(openMetadataClient.getTableByName(
                "st_ds_22964041928224.seatunnel_web.seatunnel_web.st3_cdc_sink"))
                .thenReturn(target);

        service.recordStreamingSubmission(instance, "engine-job-44");

        verify(openMetadataClient).addLineage(any(JsonNode.class));
    }

    @Test
    void prefersNativeColumnFullyQualifiedNameWhenPresent() throws Exception {
        JobInstanceVO instance = instance();
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(instance);
        when(metadataBindingDao.queryByDataSourceId(10L)).thenReturn(binding("st_ds_22964041928224"));
        when(metadataBindingDao.queryByDataSourceId(20L)).thenReturn(binding("st_ds_22978064259872"));

        OpenMetadataTable source = table(
                "source-table-id",
                "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel",
                "name", "channel_type", "description");
        source.getColumns().get(1).setFullyQualifiedName("custom.native.channel_type");
        source.getColumns().get(2).setFullyQualifiedName("custom.native.description");

        OpenMetadataTable target = table(
                "target-table-id",
                "st_ds_22978064259872.test.public.test",
                "name", "category");
        target.getColumns().get(1).setFullyQualifiedName("custom.native.category");

        when(openMetadataClient.getTableByName(
                "st_ds_22964041928224.seatunnel_web.seatunnel_web.t_seatunnel_web_alarm_channel"))
                .thenReturn(source);
        when(openMetadataClient.getTableByName("st_ds_22978064259872.test.public.test"))
                .thenReturn(target);

        service.recordBatchSubmission(instance, "engine-job-42");

        ArgumentCaptor<JsonNode> requestCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(openMetadataClient).addLineage(requestCaptor.capture());
        JsonNode columnLineage = requestCaptor.getValue()
                .path("edge").path("lineageDetails").path("columnsLineage").get(0);
        assertNotNull(columnLineage);
        assertEquals("custom.native.category", columnLineage.path("toColumn").asText());
        assertEquals(List.of(
                        "custom.native.channel_type",
                        "custom.native.description"),
                OBJECT_MAPPER.convertValue(columnLineage.path("fromColumns"),
                        OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
    }

    private static JobInstanceVO instance() {
        JobInstanceVO instance = new JobInstanceVO();
        instance.setId(100L);
        instance.setJobDefinitionId(200L);
        instance.setJobName("Customer summary");
        instance.setDefinitionMode("GUIDE_SINGLE");
        instance.setJobType("BATCH");
        instance.setRunMode("MANUAL");
        instance.setSourceDatasourceId(10L);
        instance.setSinkDatasourceId(20L);
        instance.setSourceTable("t_seatunnel_web_alarm_channel");
        instance.setSinkTable("test");
        instance.setRuntimeConfig("""
                source {
                    Jdbc {
                        database="seatunnel_web"
                        table_path="seatunnel_web.t_seatunnel_web_alarm_channel"
                        url="jdbc:mysql://192.168.100.95:33306/seatunnel_web?useSSL=false"
                    }
                }
                transform = [{
                  FieldMapper {
                    plugin_input = source
                    plugin_output = sink
                    field_mapper {
                      channel_type = category
                      description = category
                    }
                  }
                }]
                sink {
                    Jdbc {
                        database=test
                        table="public.test"
                    }
                }
                """);
        return instance;
    }

    private static MetadataSourceBinding binding(String serviceFqn) {
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setOmServiceFqn(serviceFqn);
        return binding;
    }

    private static OpenMetadataTable table(String id, String fqn, String... columns) {
        OpenMetadataTable table = new OpenMetadataTable();
        table.setId(id);
        table.setFullyQualifiedName(fqn);
        table.setServiceFullyQualifiedName(fqn.substring(0, fqn.indexOf('.')));
        table.setColumns(java.util.Arrays.stream(columns).map(name -> {
            OpenMetadataColumn column = new OpenMetadataColumn();
            column.setName(name);
            return column;
        }).toList());
        return table;
    }
}
