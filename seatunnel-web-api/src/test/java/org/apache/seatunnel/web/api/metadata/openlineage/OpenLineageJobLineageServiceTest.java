package org.apache.seatunnel.web.api.metadata.openlineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.alarm.event.JobStatusChangedEvent;
import org.apache.seatunnel.web.api.metadata.DataExplorationService;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.JobInstance;
import org.apache.seatunnel.web.dao.entity.StreamingJobInstance;
import org.apache.seatunnel.web.dao.mapper.StreamingJobInstanceMapper;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.dao.repository.impl.StreamingJobInstanceDaoImpl;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationDatabaseVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationSchemaVO;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenLineageJobLineageServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private LineageBridgeProperties properties;

    @Mock
    private OpenLineageKafkaBridge bridge;

    @Mock
    private JobInstanceDao jobInstanceDao;

    @Mock
    private StreamingJobInstanceDao streamingJobInstanceDao;

    @Mock
    private DataSourceDao dataSourceDao;

    @Mock
    private DataExplorationService exploration;

    /** Every JSON the service hands to the bridge, in publish order. */
    private List<String> published;

    private OpenLineageJobLineageService service;

    @BeforeEach
    void setUp() {
        published = new ArrayList<>();
        service = new OpenLineageJobLineageService(
                properties, bridge, jobInstanceDao, streamingJobInstanceDao, dataSourceDao,
                new OpenLineageTableFqnResolver(exploration));
        LineageBridgeProperties.Kafka kafka = new LineageBridgeProperties.Kafka();
        kafka.setEnabled(true);
        kafka.setBootstrapServers("kafka:9092");
        kafka.setSourceTopic("meta_lineage_topic");
        lenient().when(properties.getKafka()).thenReturn(kafka);
        lenient().doAnswer(invocation -> {
            published.add(invocation.getArgument(2));
            return true;
        }).when(bridge).publish(anyString(), anyString(), anyString(), anyBoolean());
        lenient().when(dataSourceDao.queryById(10L)).thenReturn(dataSource(10L, "bi_src_mysql", "MYSQL"));
        lenient().when(dataSourceDao.queryById(20L)).thenReturn(dataSource(20L, "bi_tidb_doris", "DORIS"));
    }

    private static JobInstanceVO instance() {
        JobInstanceVO instance = new JobInstanceVO();
        instance.setId(100L);
        instance.setJobDefinitionId(200L);
        instance.setJobName("Customer summary");
        instance.setJobType("BATCH");
        instance.setRunMode("MANUAL");
        instance.setSourceDatasourceId(10L);
        instance.setSinkDatasourceId(20L);
        instance.setSourceTable("seatunnel_web.t_seatunnel_web_alarm_channel");
        instance.setSinkTable("public.test");
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

    private static DataSource dataSource(Long id, String name, String dbType) {
        DataSource dataSource = new DataSource();
        dataSource.setId(id);
        dataSource.setName(name);
        dataSource.setDbType(DbType.valueOf(dbType));
        return dataSource;
    }

    private static DataSource dataSource(Long id, String name, String dbType, String url) {
        DataSource dataSource = dataSource(id, name, dbType);
        dataSource.setConnectionParams("{\"url\":\"" + url + "\",\"user\":\"root\"}");
        return dataSource;
    }

    @Test
    void publishesStartUnderTheLocalDatasetIdentity() throws Exception {
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(instance());

        service.recordStart(instance(), "engine-job-42", false);

        assertEquals(1, published.size());
        JsonNode event = MAPPER.readTree(published.get(0));
        assertEquals("START", event.path("eventType").asText());
        // The runId is deterministic, so the terminal event lands on the same run.
        assertEquals(OpenLineageEventFactory.deriveRunId(100L, "engine-job-42").toString(),
                event.path("run").path("runId").asText());
        assertEquals("Customer summary", event.path("job").path("name").asText());
        // Local identity only: datasource name as namespace, the user's table path as name.
        assertEquals("bi_src_mysql", event.path("inputs").get(0).path("namespace").asText());
        assertEquals("seatunnel_web.t_seatunnel_web_alarm_channel",
                event.path("inputs").get(0).path("name").asText());
        assertEquals("local",
                event.path("inputs").get(0).path("facets").path("seatunnelDatasource")
                        .path("identityType").asText());
        // columnLineage attached because the mapper produced two target fields.
        assertFalse(event.path("outputs").get(0).path("facets").path("columnLineage")
                .path("fields").isEmpty());
    }

    @Test
    void publishesBatchCompletionWithCatalogTableFqnsAndMatchingFieldReferences() throws Exception {
        long sourceId = 22980182904096L;
        long sinkId = 23132197819872L;
        JobInstanceVO details = instance();
        details.setSourceDatasourceId(sourceId);
        details.setSinkDatasourceId(sinkId);
        details.setSourceTable("DAMENG.TEST.STUDENT");
        details.setSinkTable("public.STUDENT");
        details.setRuntimeConfig("""
                source { Jdbc { table_path = "DAMENG.TEST.STUDENT" } }
                transform = [{
                    FieldMapper {
                        plugin_input = source
                        plugin_output = sink
                        field_mapper { NAME = STUDENT_NAME }
                    }
                }]
                sink { Jdbc { table = "public.STUDENT" } }
                """);
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(details);
        when(dataSourceDao.queryById(sourceId)).thenReturn(dataSource(sourceId, "dameng", "DAMENG"));
        when(dataSourceDao.queryById(sinkId)).thenReturn(dataSource(sinkId, "vastbase", "VASTBASE"));
        mockCatalog(sourceId, "st_ds_22980182904096.default", "TEST");
        mockCatalog(sinkId, "st_ds_23132197819872.default", "public");

        service.onBatchJobStatusChanged(new JobStatusChangedEvent(
                this, 100L, 200L, JobStatus.FINISHED, JobStatus.RUNNING, null, "engine-job-42"));

        assertEquals(1, published.size());
        JsonNode event = MAPPER.readTree(published.getFirst());
        assertEquals("COMPLETE", event.path("eventType").asText());
        JsonNode input = event.path("inputs").get(0);
        JsonNode output = event.path("outputs").get(0);
        assertEquals("st_ds_22980182904096.default.TEST.STUDENT", input.path("name").asText());
        assertEquals("st_ds_23132197819872.default.public.STUDENT", output.path("name").asText());
        assertEquals("st_ds_23132197819872", output.at("/facets/datasource/name").asText());
        assertEquals("st_ds_23132197819872", output.at("/facets/seatunnelDatasource/serviceName").asText());
        assertEquals("fqn", output.at("/facets/seatunnelDatasource/identityType").asText());
        JsonNode reference = output.at("/facets/columnLineage/fields/STUDENT_NAME/inputFields/0");
        assertEquals(input.path("namespace"), reference.path("namespace"));
        assertEquals(input.path("name"), reference.path("name"));
        assertEquals("NAME", reference.path("field").asText());
    }

    @Test
    void resolvesTwoPartPostgresPathsAndKeepsLocalIdentityWhenCatalogIsUnavailable() throws Exception {
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(instance());
        when(dataSourceDao.queryById(20L)).thenReturn(dataSource(20L, "postgres", "POSTGRE_SQL"));
        when(exploration.listDatabases(10L)).thenThrow(new IllegalStateException("catalog unavailable"));
        mockCatalog(20L, "bound_postgres.\"sales.archive\"", "public");

        service.recordStart(instance(), "engine-job-42", false);

        assertEquals(1, published.size());
        JsonNode event = MAPPER.readTree(published.getFirst());
        assertEquals("seatunnel_web.t_seatunnel_web_alarm_channel", event.at("/inputs/0/name").asText());
        assertEquals("local", event.at("/inputs/0/facets/seatunnelDatasource/identityType").asText());
        assertEquals("bound_postgres.\"sales.archive\".public.test", event.at("/outputs/0/name").asText());
        assertEquals("bound_postgres", event.at("/outputs/0/facets/datasource/name").asText());
    }

    @Test
    void retainsLocalPathWhenTwoDatabasesContainTheSameSchema() throws Exception {
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(instance());
        when(dataSourceDao.queryById(20L)).thenReturn(dataSource(20L, "postgres", "POSTGRE_SQL"));
        when(exploration.listDatabases(20L)).thenReturn(List.of(
                database("bound_postgres.sales"), database("bound_postgres.archive")));
        when(exploration.listSchemas(20L, "bound_postgres.sales"))
                .thenReturn(List.of(schema("bound_postgres.sales.public")));
        when(exploration.listSchemas(20L, "bound_postgres.archive"))
                .thenReturn(List.of(schema("bound_postgres.archive.public")));

        service.recordStart(instance(), "engine-job-42", false);

        assertEquals(1, published.size());
        assertEquals("public.test", MAPPER.readTree(published.getFirst()).at("/outputs/0/name").asText());
    }

    @Test
    void publishesOneRunPerDynamicJdbcTableAndRetriesOnlyUnacknowledgedTerminals() throws Exception {
        JobInstanceVO details = instance();
        details.setSinkTable("[\"orders\",\"customers\"]");
        details.setRuntimeConfig("""
                source { Jdbc {
                    database = kingbase
                    table_list = [
                        { table_path = "kingbase.public.orders" },
                        { table_path = "kingbase.public.customers" }
                    ]
                } }
                transform {}
                sink { Jdbc {
                    database = postgres
                    table = "public.${table_name}"
                    generate_sink_sql = true
                } }
                """);
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(details);
        when(dataSourceDao.queryById(10L)).thenReturn(dataSource(10L, "kingbase", "KINGBASE"));
        when(dataSourceDao.queryById(20L)).thenReturn(dataSource(20L, "zeonedb", "ZEONEDB"));
        mockCatalog(10L, "st_ds_10.default", "public");
        when(exploration.listDatabases(20L)).thenReturn(List.of(database("st_ds_20.postgres")));
        List<JsonNode> attempts = new ArrayList<>();
        doAnswer(invocation -> {
            JsonNode message = MAPPER.readTree(invocation.<String>getArgument(2));
            attempts.add(message);
            assertEquals(message.at("/run/runId").asText(), invocation.getArgument(1));
            return attempts.size() != 4;
        }).when(bridge).publish(anyString(), anyString(), anyString(), anyBoolean());

        service.recordStart(details, "engine-job-42", false);
        service.onBatchJobStatusChanged(new JobStatusChangedEvent(
                this, 100L, 200L, JobStatus.FINISHED, JobStatus.RUNNING, null, "engine-job-42"));
        service.pulseBatchJobs();

        assertEquals(5, attempts.size());
        assertDynamicTablePair(attempts.get(0), "START", "orders");
        assertDynamicTablePair(attempts.get(1), "START", "customers");
        assertDynamicTablePair(attempts.get(2), "COMPLETE", "orders");
        assertDynamicTablePair(attempts.get(3), "COMPLETE", "customers");
        assertEquals(attempts.get(0).at("/run/runId"), attempts.get(2).at("/run/runId"));
        assertEquals(attempts.get(1).at("/run/runId"), attempts.get(3).at("/run/runId"));
        assertFalse(attempts.get(0).at("/run/runId").equals(attempts.get(1).at("/run/runId")));
        assertEquals(attempts.get(3), attempts.get(4), "retry must send the same unacknowledged event");
    }

    private static void assertDynamicTablePair(JsonNode message, String eventType, String table) {
        assertEquals(eventType, message.path("eventType").asText());
        assertEquals(100L, message.at("/run/facets/seatunnel/instanceId").asLong());
        assertEquals(1, message.path("inputs").size());
        assertEquals(1, message.path("outputs").size());
        assertEquals("st_ds_10.default.public." + table, message.at("/inputs/0/name").asText());
        assertEquals("st_ds_20.postgres.public." + table, message.at("/outputs/0/name").asText());
    }

    private static DataExplorationDatabaseVO database(String fqn) {
        DataExplorationDatabaseVO database = new DataExplorationDatabaseVO();
        database.setFullyQualifiedName(fqn);
        return database;
    }

    private static DataExplorationSchemaVO schema(String fqn) {
        DataExplorationSchemaVO schema = new DataExplorationSchemaVO();
        schema.setFullyQualifiedName(fqn);
        return schema;
    }

    private void mockCatalog(long datasourceId, String databaseFqn, String schemaName) {
        when(exploration.listDatabases(datasourceId)).thenReturn(List.of(database(databaseFqn)));
        when(exploration.listSchemas(datasourceId, databaseFqn))
                .thenReturn(List.of(schema(databaseFqn + "." + schemaName)));
    }

    @Test
    void readsTheTableOfAQueryOnlySourceWhenTheInstanceSnapshotIsEmpty() throws Exception {
        JobInstanceVO submitted = instance();
        submitted.setSourceTable(null);
        submitted.setRuntimeConfig("""
                source {
                    Jdbc {
                        url="jdbc:mysql://192.168.100.95:33306/seatunnel_web"
                        query="select * from seatunnel_web.orders"
                    }
                }
                sink {
                    Jdbc {
                        table="public.test"
                    }
                }
                """);

        service.recordStart(submitted, "engine-job-42", false);

        assertEquals(1, published.size());
        JsonNode event = MAPPER.readTree(published.get(0));
        assertEquals("START", event.path("eventType").asText());
        assertEquals("seatunnel_web.orders", event.path("inputs").get(0).path("name").asText());
    }

    @Test
    void prefersTheSubmittedConfigurationOverTheInstanceTable() throws Exception {
        JobInstanceVO submitted = instance();
        submitted.setSourceTable("seatunnel_web.stale_table");
        submitted.setRuntimeConfig("""
                source {
                    Jdbc {
                        url="jdbc:mysql://192.168.100.95:33306/seatunnel_web"
                        table_path="seatunnel_web.orders"
                    }
                }
                sink {
                    Jdbc {
                        table="public.test"
                    }
                }
                """);

        service.recordStart(submitted, "engine-job-63", false);

        assertEquals(1, published.size());
        JsonNode event = MAPPER.readTree(published.get(0));
        assertEquals("seatunnel_web.orders", event.path("inputs").get(0).path("name").asText());
    }

    @Test
    void skipsMixedKafkaAndDatabaseJobsForRelationalLineage() {
        JobInstanceVO submitted = instance();
        submitted.setSourceTable("instance.source.table");
        submitted.setSinkTable("instance.sink.table");
        submitted.setRuntimeConfig("""
                source { Kafka { topic="orders-topic" } }
                sink { Jdbc { table="public.orders" } }
                """);
        when(dataSourceDao.queryById(10L)).thenReturn(dataSource(10L, "source_kafka", "KAFKA"));

        service.recordStart(submitted, "engine-job-77", true);

        assertTrue(published.isEmpty());
        verifyNoInteractions(bridge, exploration);
    }

    /**
     * A pattern subscription has no dataset: the topic option is a regular expression, and the
     * instance snapshot of such a job stores that regex as its "table". Neither may be published.
     */
    @Test
    void doesNotPublishAPatternSubscriptionAsADataset() {
        JobInstanceVO submitted = instance();
        submitted.setSourceTable("orders.$");
        submitted.setSinkTable("orders-dwd-topic");
        submitted.setRuntimeConfig("""
                source {
                    Kafka {
                        topic="orders.$"
                        pattern=true
                        bootstrap.servers="kafka-a:9092"
                    }
                }
                sink {
                    Kafka {
                        topic="orders-dwd-topic"
                        bootstrap.servers="kafka-b:9092"
                    }
                }
                """);

        service.recordStart(submitted, "engine-job-78", true);

        assertTrue(published.isEmpty());
    }

    /**
     * A stored runtime config may still carry the legacy shape where the regex sits in
     * "pattern". Such an instance must not publish the regex, and the instance-table fallback of
     * the same side is suppressed as well: the snapshot of such a job holds the regex too.
     */
    @Test
    void doesNotPublishALegacyPatternExpression() {
        JobInstanceVO submitted = instance();
        submitted.setSourceTable("orders.$");
        submitted.setSinkTable("orders-dwd-topic");
        submitted.setRuntimeConfig("""
                source {
                    Kafka {
                        topic="orders.$"
                        pattern="orders.$"
                        bootstrap.servers="kafka-a:9092"
                    }
                }
                sink {
                    Kafka {
                        topic="orders-dwd-topic"
                        bootstrap.servers="kafka-b:9092"
                    }
                }
                """);

        service.recordStart(submitted, "engine-job-79", true);

        assertTrue(published.isEmpty());
    }

    /**
     * A pattern plugin on a side suppresses the instance-table fallback for that whole side: the
     * stored snapshot names the regex, so the literal plugin's own dynamic table cannot be
     * recovered from it either.
     */
    @Test
    void aPatternSubscriptionSuppressesTheInstanceFallbackOfTheSameSide() {
        JobInstanceVO submitted = instance();
        submitted.setSourceTable("orders.$");
        submitted.setSinkTable("orders-dwd-topic");
        submitted.setRuntimeConfig("""
                source = [
                    { Kafka { topic="orders.$", pattern=true, bootstrap.servers="kafka-a:9092" } },
                    { Jdbc { table="${table_name}", url="jdbc:mysql://host:3306/db" } }
                ]
                sink {
                    Kafka {
                        topic="orders-dwd-topic"
                        bootstrap.servers="kafka-b:9092"
                    }
                }
                """);

        service.recordStart(submitted, "engine-job-80", true);

        assertTrue(published.isEmpty());
    }

    @Test
    void fallsBackToTheInstanceTableWhenTheSinkUsesADynamicTableName() throws Exception {
        JobInstanceVO submitted = instance();
        submitted.setSinkTable("public.dynamic_sink");
        submitted.setRuntimeConfig("""
                source {
                    Jdbc {
                        url="jdbc:mysql://192.168.100.95:33306/seatunnel_web"
                        table_path="seatunnel_web.orders"
                    }
                }
                sink {
                    Jdbc {
                        table="${table_name}"
                    }
                }
                """);

        service.recordStart(submitted, "engine-job-66", false);

        assertEquals(1, published.size());
        JsonNode event = MAPPER.readTree(published.get(0));
        assertEquals("seatunnel_web.orders", event.path("inputs").get(0).path("name").asText());
        assertEquals("public.dynamic_sink", event.path("outputs").get(0).path("name").asText());
    }

    /**
     * A hyphen is a legal part of table and topic names: the instance snapshot fallback accepts
     * it, exactly like the plugin path of the topology.
     */
    @Test
    void publishesAHyphenatedTableNameFromTheInstanceSnapshot() throws Exception {
        JobInstanceVO submitted = instance();
        submitted.setSinkTable("ods.my-events");
        submitted.setRuntimeConfig("""
                source {
                    Jdbc {
                        url="jdbc:mysql://192.168.100.95:33306/seatunnel_web"
                        table_path="seatunnel_web.orders"
                    }
                }
                sink {
                    Jdbc {
                        table="${table_name}"
                    }
                }
                """);

        service.recordStart(submitted, "engine-job-66", false);

        assertEquals(1, published.size());
        JsonNode event = MAPPER.readTree(published.get(0));
        assertEquals("ods.my-events", event.path("outputs").get(0).path("name").asText());
    }

    /**
     * A table list element of the instance snapshot is qualified with its database, the same way
     * the topology qualifies a plugin table, so one physical table keeps one dataset identity.
     */
    @Test
    void qualifiesTheDatabaseOfAnInstanceTableListElement() throws Exception {
        JobInstanceVO submitted = instance();
        submitted.setSinkTable("""
                [{"database":"ods","table":"events"},{"table":"public.plain"}]
                """);
        submitted.setRuntimeConfig("""
                source {
                    Jdbc {
                        url="jdbc:mysql://192.168.100.95:33306/seatunnel_web"
                        table_path="seatunnel_web.orders"
                    }
                }
                sink {
                    Jdbc {
                        table="${table_name}"
                    }
                }
                """);

        service.recordStart(submitted, "engine-job-66", false);

        assertEquals(1, published.size());
        JsonNode outputs = MAPPER.readTree(published.get(0)).path("outputs");
        assertEquals(2, outputs.size());
        assertEquals("ods.events", outputs.get(0).path("name").asText());
        assertEquals("public.plain", outputs.get(1).path("name").asText());
    }

    @Test
    void publishesTheTableNamesOfACdcSource() throws Exception {
        JobInstanceVO submitted = instance();
        submitted.setSourceTable(null);
        submitted.setRuntimeConfig("""
                source {
                    MySQL-CDC {
                        "table-names"=["inventory.orders","inventory.customers"]
                    }
                }
                sink {
                    Jdbc {
                        table="public.test"
                    }
                }
                """);

        service.recordStart(submitted, "engine-job-67", false);

        assertEquals(1, published.size());
        JsonNode event = MAPPER.readTree(published.get(0));
        assertEquals(2, event.path("inputs").size());
        assertEquals("inventory.orders", event.path("inputs").get(0).path("name").asText());
        assertEquals("inventory.customers", event.path("inputs").get(1).path("name").asText());
    }

    @Test
    void doesNotPublishRawScriptSqlAsATable() {
        JobInstanceVO submitted = instance();
        submitted.setSourceTable("select * from (select id from db.t) x");
        submitted.setRuntimeConfig("""
                source {
                    Jdbc {
                        query="select * from (select id from db.t) x"
                    }
                }
                sink {
                    Jdbc {
                        table="public.test"
                    }
                }
                """);

        service.recordStart(submitted, "engine-job-68", false);

        assertTrue(published.isEmpty());
    }

    @Test
    void publishesEveryTableOfAQueryOnlyJoin() throws Exception {
        JobInstanceVO submitted = instance();
        submitted.setSourceTable(null);
        submitted.setRuntimeConfig("""
                source {
                    Jdbc {
                        url="jdbc:mysql://192.168.100.95:33306/seatunnel_web"
                        query="select o.id as order_id, c.name as customer_name from seatunnel_web.orders o join seatunnel_web.customers c on o.id = c.id"
                    }
                }
                sink {
                    Jdbc {
                        table="public.test"
                    }
                }
                """);

        service.recordStart(submitted, "engine-job-69", false);

        assertEquals(1, published.size());
        JsonNode event = MAPPER.readTree(published.get(0));
        assertEquals(2, event.path("inputs").size());
        assertEquals("seatunnel_web.orders", event.path("inputs").get(0).path("name").asText());
        assertEquals("seatunnel_web.customers", event.path("inputs").get(1).path("name").asText());
        // The source query has no transform mapping, so only table level lineage is known.
        assertTrue(event.path("outputs").get(0).path("facets").path("columnLineage").isMissingNode());
    }

    @Test
    void publishesEverySourceTableOfAMultiTableJob() throws Exception {
        JobInstanceVO submitted = instance();
        submitted.setSourceTable("[\"seatunnel_web.orders\",\"seatunnel_web.customers\"]");
        submitted.setRuntimeConfig("""
                source {
                    Jdbc {
                        url="jdbc:mysql://192.168.100.95:33306/seatunnel_web"
                        table_list=["seatunnel_web.orders","seatunnel_web.customers"]
                    }
                }
                sink {
                    Jdbc {
                        table="public.test"
                    }
                }
                """);

        service.recordStart(submitted, "engine-job-60", false);

        assertEquals(1, published.size());
        JsonNode event = MAPPER.readTree(published.get(0));
        assertEquals(2, event.path("inputs").size());
        assertEquals("seatunnel_web.orders", event.path("inputs").get(0).path("name").asText());
        assertEquals("seatunnel_web.customers", event.path("inputs").get(1).path("name").asText());
        // The JSON table list must never leak into the dataset name.
        assertEquals("bi_src_mysql", event.path("inputs").get(0).path("namespace").asText());
    }

    @Test
    void publishesEverySourceTableStoredAsAJsonListWithoutAReadableConfiguration() throws Exception {
        JobInstanceVO submitted = instance();
        submitted.setSourceTable("[\"seatunnel_web.orders\",\"seatunnel_web.customers\"]");
        submitted.setRuntimeConfig("");

        service.recordStart(submitted, "engine-job-61", false);

        assertEquals(1, published.size());
        JsonNode event = MAPPER.readTree(published.get(0));
        assertEquals(2, event.path("inputs").size());
        assertEquals("seatunnel_web.orders", event.path("inputs").get(0).path("name").asText());
        assertEquals("seatunnel_web.customers", event.path("inputs").get(1).path("name").asText());
    }

    @Test
    void publishesEverySourceTableStoredAsACommaSeparatedList() throws Exception {
        JobInstanceVO submitted = instance();
        submitted.setSourceTable("seatunnel_web.orders,seatunnel_web.customers");
        submitted.setRuntimeConfig("");

        service.recordStart(submitted, "engine-job-62", false);

        assertEquals(1, published.size());
        JsonNode event = MAPPER.readTree(published.get(0));
        assertEquals(2, event.path("inputs").size());
        assertEquals("seatunnel_web.orders", event.path("inputs").get(0).path("name").asText());
        assertEquals("seatunnel_web.customers", event.path("inputs").get(1).path("name").asText());
    }

    @Test
    void resolvesTheDatasourceOfEveryPluginByItsConnection() throws Exception {
        JobInstanceVO submitted = instance();
        submitted.setSourceDatasourceId(10L);
        submitted.setRuntimeConfig("""
                source = [
                  { Jdbc {
                      url="jdbc:mysql://192.168.100.95:33306/seatunnel_web"
                      table_path="seatunnel_web.orders"
                  } },
                  { Jdbc {
                      url="jdbc:mysql://192.168.100.96:33306/other_db"
                      table_path="other_db.customers"
                  } }
                ]
                sink {
                    Jdbc { table="public.test" }
                }
                """);
        lenient().when(dataSourceDao.queryAll()).thenReturn(List.of(
                dataSource(10L, "bi_src_mysql", "MYSQL", "jdbc:mysql://192.168.100.95:33306/seatunnel_web"),
                dataSource(30L, "bi_other_mysql", "MYSQL", "jdbc:mysql://192.168.100.96:33306/other_db")));
        lenient().when(dataSourceDao.queryById(30L))
                .thenReturn(dataSource(30L, "bi_other_mysql", "MYSQL"));

        service.recordStart(submitted, "engine-job-64", false);

        assertEquals(1, published.size());
        JsonNode event = MAPPER.readTree(published.get(0));
        assertEquals(2, event.path("inputs").size());
        // Each plugin keeps its own datasource: the namespaces differ.
        assertEquals("bi_src_mysql", event.path("inputs").get(0).path("namespace").asText());
        assertEquals("seatunnel_web.orders", event.path("inputs").get(0).path("name").asText());
        assertEquals("bi_other_mysql", event.path("inputs").get(1).path("namespace").asText());
        assertEquals("other_db.customers", event.path("inputs").get(1).path("name").asText());
        assertEquals("st_ds_30", event.path("inputs").get(1).path("facets")
                .path("datasource").path("name").asText());
    }

    @Test
    void skipsTheTableOfAPluginWhoseDatasourceStaysAmbiguous() throws Exception {
        JobInstanceVO submitted = instance();
        submitted.setSourceDatasourceId(10L);
        submitted.setRuntimeConfig("""
                source = [
                  { Jdbc {
                      url="jdbc:mysql://192.168.100.95:33306/seatunnel_web"
                      table_path="seatunnel_web.orders"
                  } },
                  { Jdbc {
                      url="jdbc:mysql://192.168.100.97:33306/other_db"
                      table_path="other_db.customers"
                  } }
                ]
                sink {
                    Jdbc { table="public.test" }
                }
                """);
        // Two local datasources share the connection of the second plugin.
        lenient().when(dataSourceDao.queryAll()).thenReturn(List.of(
                dataSource(10L, "bi_src_mysql", "MYSQL", "jdbc:mysql://192.168.100.95:33306/seatunnel_web"),
                dataSource(30L, "bi_other_mysql", "MYSQL", "jdbc:mysql://192.168.100.97:33306/other_db"),
                dataSource(31L, "bi_other_mysql_copy", "MYSQL", "jdbc:mysql://192.168.100.97:33306/other_db")));

        service.recordStart(submitted, "engine-job-65", false);

        assertEquals(1, published.size());
        JsonNode event = MAPPER.readTree(published.get(0));
        // The ambiguous plugin contributes no table, the resolved one still does.
        assertEquals(1, event.path("inputs").size());
        assertEquals("seatunnel_web.orders", event.path("inputs").get(0).path("name").asText());
    }

    @Test
    void mapsEveryBatchTerminalStatusPerTheDesignMatrix() throws Exception {
        when(jobInstanceDao.selectDetailById(anyLong())).thenAnswer(invocation -> {
            JobInstanceVO details = instance();
            details.setId(invocation.getArgument(0));
            details.setEngineJobId("engine-job-42");
            return details;
        });

        // One terminal per instance: the real update is guarded to a single final transition.
        service.onBatchJobStatusChanged(new JobStatusChangedEvent(
                this, 101L, 200L, JobStatus.FINISHED, JobStatus.RUNNING, null, "engine-job-42"));
        service.onBatchJobStatusChanged(new JobStatusChangedEvent(
                this, 102L, 200L, JobStatus.FAILED, JobStatus.RUNNING, "engine crash", "engine-job-42"));
        service.onBatchJobStatusChanged(new JobStatusChangedEvent(
                this, 103L, 200L, JobStatus.CANCELED, JobStatus.RUNNING, null, "engine-job-42"));
        service.onBatchJobStatusChanged(new JobStatusChangedEvent(
                this, 104L, 200L, JobStatus.SAVEPOINT_DONE, JobStatus.RUNNING, null, "engine-job-42"));

        assertEquals(4, published.size());
        assertEquals("COMPLETE", MAPPER.readTree(published.get(0)).path("eventType").asText());
        assertEquals("FAIL", MAPPER.readTree(published.get(1)).path("eventType").asText());
        assertEquals("engine crash", MAPPER.readTree(published.get(1)).path("run")
                .path("facets").path("seatunnel").path("errorMessage").asText());
        assertEquals("ABORT", MAPPER.readTree(published.get(2)).path("eventType").asText());
        assertEquals("ABORT", MAPPER.readTree(published.get(3)).path("eventType").asText());
        // prevStatus comes from the event, not from the terminal status itself.
        assertEquals("RUNNING", MAPPER.readTree(published.get(0)).path("run")
                .path("facets").path("seatunnel").path("prevStatus").asText());
        // All terminals share the START runId of their instance.
        assertEquals(OpenLineageEventFactory.deriveRunId(101L, "engine-job-42").toString(),
                MAPPER.readTree(published.get(0)).path("run").path("runId").asText());
    }

    /**
     * The status change event does not carry the previous status, so the terminal takes the
     * status the pulse saw last for that instance.
     */
    @Test
    void theTerminalTakesTheLastSeenStatusWhenTheEventCarriesNone() throws Exception {
        JobInstance running = new JobInstance();
        running.setId(100L);
        running.setJobStatus(JobStatus.RUNNING);
        when(jobInstanceDao.listRunningLikeInstances()).thenReturn(List.of(running));
        JobInstanceVO finished = instance();
        finished.setJobStatus("FINISHED");
        finished.setEngineJobId("engine-job-42");
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(finished);

        service.pulseBatchJobs();
        service.onBatchJobStatusChanged(new JobStatusChangedEvent(
                this, 100L, 200L, JobStatus.FINISHED, null, null, "engine-job-42"));

        assertEquals(1, published.size());
        assertEquals("RUNNING", MAPPER.readTree(published.get(0)).path("run")
                .path("facets").path("seatunnel").path("prevStatus").asText());
    }

    @Test
    void repeatedTerminalEventForTheSameInstanceIsPublishedOnce() {
        JobInstanceVO details = instance();
        details.setEngineJobId("engine-job-42");
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(details);

        service.onBatchJobStatusChanged(new JobStatusChangedEvent(
                this, 100L, 200L, JobStatus.FINISHED, JobStatus.RUNNING, null, "engine-job-42"));
        service.onBatchJobStatusChanged(new JobStatusChangedEvent(
                this, 100L, 200L, JobStatus.FINISHED, JobStatus.RUNNING, null, "engine-job-42"));

        assertEquals(1, published.size());
    }

    @Test
    void batchPulseReportsAnInstanceFailedWithoutAStatusEvent() throws Exception {
        JobInstance running = new JobInstance();
        running.setId(100L);
        running.setJobStatus(JobStatus.RUNNING);
        when(jobInstanceDao.listRunningLikeInstances())
                .thenReturn(List.of(running))
                .thenReturn(List.of());
        JobInstanceVO failed = instance();
        failed.setJobStatus("FAILED");
        failed.setEngineJobId("engine-job-42");
        failed.setErrorMessage("engine client died");
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(failed);

        // Pulse 1 tracks the running instance, pulse 2 sees the bulk failure and reports it.
        service.pulseBatchJobs();
        service.pulseBatchJobs();

        assertEquals(1, published.size());
        JsonNode terminal = MAPPER.readTree(published.get(0));
        assertEquals("FAIL", terminal.path("eventType").asText());
        assertEquals("engine client died", terminal.path("run").path("facets")
                .path("seatunnel").path("errorMessage").asText());
    }

    @Test
    void aTerminalThatWasNotDeliveredIsPublishedAgainByTheNextPulse() {
        JobInstance running = new JobInstance();
        running.setId(100L);
        running.setJobStatus(JobStatus.RUNNING);
        when(jobInstanceDao.listRunningLikeInstances())
                .thenReturn(List.of(running))
                .thenReturn(List.of())
                .thenReturn(List.of());
        JobInstanceVO failed = instance();
        failed.setJobStatus("FAILED");
        failed.setEngineJobId("engine-job-42");
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(failed);
        // The broker does not acknowledge the first terminal.
        List<String> attempts = new ArrayList<>();
        AtomicInteger deliveries = new AtomicInteger();
        lenient().doAnswer(invocation -> {
            attempts.add(invocation.getArgument(2));
            return deliveries.getAndIncrement() == 0 ? false : true;
        }).when(bridge).publish(anyString(), anyString(), anyString(), anyBoolean());

        // Pulse 1 tracks the running instance, pulse 2 sees the bulk failure and reports it.
        service.pulseBatchJobs();
        service.pulseBatchJobs();

        // The undelivered terminal is not forgotten: the next pulse publishes it again.
        assertEquals(1, attempts.size());
        service.pulseBatchJobs();
        assertEquals(2, attempts.size());
    }

    /**
     * A job that finishes before the pulse ever observed it still gets its terminal published
     * when the broker was unavailable at that moment.
     */
    @Test
    void aTerminalThatWasNotDeliveredIsRetriedEvenForAnUnobservedInstance() throws Exception {
        JobInstanceVO finished = instance();
        finished.setJobStatus("FINISHED");
        finished.setEngineJobId("engine-job-42");
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(finished);
        // The broker does not acknowledge the first terminal.
        AtomicInteger deliveries = new AtomicInteger();
        lenient().doAnswer(invocation -> {
            boolean delivered = deliveries.incrementAndGet() > 1;
            if (delivered) {
                published.add(invocation.getArgument(2));
            }
            return delivered;
        }).when(bridge).publish(anyString(), anyString(), anyString(), anyBoolean());

        service.onBatchJobStatusChanged(new JobStatusChangedEvent(
                this, 100L, 200L, JobStatus.FINISHED, JobStatus.RUNNING, null, "engine-job-42"));
        assertEquals(0, published.size());

        // The pulse publishes the pending terminal even though the instance was never running.
        service.pulseBatchJobs();

        assertEquals(1, published.size());
        assertEquals("COMPLETE", MAPPER.readTree(published.get(0)).path("eventType").asText());
    }

    @Test
    void pendingSubmissionPublishesNothing() {
        service.onBatchJobStatusChanged(new JobStatusChangedEvent(
                this, 100L, null, JobStatus.STARTING, null, "no engine id", null));

        assertTrue(published.isEmpty(), "STARTING must not emit an event");
    }

    @Test
    void unknownStatusesLikeRunningOrFailingPublishNothing() {
        service.onBatchJobStatusChanged(new JobStatusChangedEvent(
                this, 100L, null, JobStatus.RUNNING, null, null, "engine-job-42"));
        service.onBatchJobStatusChanged(new JobStatusChangedEvent(
                this, 100L, null, JobStatus.FAILING, null, null, "engine-job-42"));
        service.onBatchJobStatusChanged(new JobStatusChangedEvent(
                this, 100L, null, JobStatus.UNKNOWABLE, null, null, "engine-job-42"));

        assertTrue(published.isEmpty(), "non-terminal batch statuses must not emit an event");
    }

    @Test
    void streamingPulseSendsHeartbeatForEveryRunningInstance() throws Exception {
        JobInstanceVO listed = instance();
        listed.setJobStatus("RUNNING");
        listed.setEngineJobId("engine-job-50");
        when(streamingJobInstanceDao.listRunning()).thenReturn(List.of(listed));
        when(streamingJobInstanceDao.selectDetailById(100L)).thenReturn(listed);

        service.pulseStreamingJobs();

        assertEquals(1, published.size());
        JsonNode heartbeat = MAPPER.readTree(published.get(0));
        assertEquals("RUNNING", heartbeat.path("eventType").asText());
        assertTrue(heartbeat.path("run").path("facets").path("seatunnel")
                .path("lastHeartbeat").asText().length() > 0);
        assertTrue(heartbeat.path("run").path("facets").path("seatunnel").path("streaming").asBoolean());

        // A second pulse for the same still-running instance repeats the heartbeat.
        service.pulseStreamingJobs();
        assertEquals(2, published.size());
    }

    @Test
    void streamingVanishEmitsCompleteForAFinishedEngineResult() throws Exception {
        JobInstanceVO listed = instance();
        listed.setJobStatus("RUNNING");
        listed.setEngineJobId("engine-job-51");
        when(streamingJobInstanceDao.listRunning())
                .thenReturn(List.of(listed))
                .thenReturn(List.of());
        JobInstanceVO finished = instance();
        finished.setJobStatus("FINISHED");
        finished.setEngineJobId("engine-job-51");
        when(streamingJobInstanceDao.selectDetailById(100L))
                .thenReturn(listed)
                .thenReturn(finished);

        // Pulse 1 marks the instance as monitored, pulse 2 sees it gone and reads the status.
        service.pulseStreamingJobs();
        service.pulseStreamingJobs();

        assertEquals(2, published.size());
        JsonNode terminal = MAPPER.readTree(published.get(1));
        assertEquals("COMPLETE", terminal.path("eventType").asText());
        assertEquals("RUNNING", terminal.path("run").path("facets").path("seatunnel")
                .path("prevStatus").asText());
        // A vanished instance must not emit the terminal twice.
        service.pulseStreamingJobs();
        assertEquals(2, published.size());
    }

    @Test
    void streamingVanishEmitsFailForFailedStatus() throws Exception {
        JobInstanceVO listed = instance();
        listed.setJobStatus("RUNNING");
        listed.setEngineJobId("engine-job-52");
        when(streamingJobInstanceDao.listRunning())
                .thenReturn(List.of(listed))
                .thenReturn(List.of());
        JobInstanceVO failed = instance();
        failed.setJobStatus("FAILED");
        failed.setEngineJobId("engine-job-52");
        failed.setErrorMessage("engine died");
        when(streamingJobInstanceDao.selectDetailById(100L))
                .thenReturn(listed)
                .thenReturn(failed);

        service.pulseStreamingJobs();
        service.pulseStreamingJobs();

        JsonNode terminal = MAPPER.readTree(published.get(1));
        assertEquals("FAIL", terminal.path("eventType").asText());
        assertEquals("engine died", terminal.path("run").path("facets").path("seatunnel")
                .path("errorMessage").asText());
    }

    @Test
    void streamingVanishWithANonTerminalStatusPublishesNothing() throws Exception {
        JobInstanceVO listed = instance();
        listed.setJobStatus("DOING_SAVEPOINT");
        listed.setEngineJobId("engine-job-53");
        when(streamingJobInstanceDao.listRunning())
                .thenReturn(List.of(listed))
                .thenReturn(List.of());
        JobInstanceVO unknown = instance();
        unknown.setJobStatus("STARTING");
        unknown.setEngineJobId("engine-job-53");
        when(streamingJobInstanceDao.selectDetailById(100L))
                .thenReturn(listed)
                .thenReturn(unknown);

        service.pulseStreamingJobs();
        service.pulseStreamingJobs();

        // No heartbeat (status was not RUNNING) and no terminal (status is not a recognized one).
        verifyNoInteractions(bridge);
    }

    @Test
    void legacyCancelledStatusesStillAbort() throws Exception {
        JobInstanceVO listed = instance();
        listed.setJobStatus("RUNNING");
        listed.setEngineJobId("engine-job-54");
        when(streamingJobInstanceDao.listRunning())
                .thenReturn(List.of(listed))
                .thenReturn(List.of());
        JobInstanceVO stopped = instance();
        stopped.setJobStatus("STOPPED");
        stopped.setEngineJobId("engine-job-54");
        when(streamingJobInstanceDao.selectDetailById(100L))
                .thenReturn(listed)
                .thenReturn(stopped);

        service.pulseStreamingJobs();
        service.pulseStreamingJobs();

        assertEquals("ABORT", MAPPER.readTree(published.get(1)).path("eventType").asText());
    }

    @Test
    void nonRunningListingStatusesAreNeverTrackedAsHeartbeats() {
        JobInstanceVO initializing = instance();
        initializing.setJobStatus("INITIALIZING");
        initializing.setEngineJobId("engine-job-55");
        when(streamingJobInstanceDao.listRunning()).thenReturn(List.of(initializing));

        service.pulseStreamingJobs();

        verifyNoInteractions(bridge);
    }

    /**
     * Regression for the missing status conversion: the real DAO path must hand "RUNNING" to the
     * pulse, which then sends the heartbeat and detects the later terminal.
     */
    @Test
    void theRealStreamingDaoConversionPublishesAHeartbeatAndTheLaterTerminal() throws Exception {
        StreamingJobInstanceMapper mapper = mock(StreamingJobInstanceMapper.class);
        StreamingJobInstance running = StreamingJobInstance.builder()
                .id(100L)
                .jobDefinitionId(200L)
                .clientId(1L)
                .engineJobId("engine-job-90")
                .jobStatus(JobStatus.RUNNING)
                .build();
        when(mapper.selectList(any())).thenReturn(List.of(running)).thenReturn(List.of());
        StreamingJobInstanceDaoImpl realDao = new StreamingJobInstanceDaoImpl(mapper);
        ReflectionTestUtils.setField(realDao, "streamingJobInstanceMapper", mapper);
        service = new OpenLineageJobLineageService(
                properties, bridge, jobInstanceDao, realDao, dataSourceDao,
                new OpenLineageTableFqnResolver(exploration));

        JobInstanceVO runningDetails = instance();
        runningDetails.setJobStatus("RUNNING");
        runningDetails.setEngineJobId("engine-job-90");
        JobInstanceVO finishedDetails = instance();
        finishedDetails.setJobStatus("FINISHED");
        finishedDetails.setEngineJobId("engine-job-90");
        when(mapper.selectDetailById(100L)).thenReturn(runningDetails).thenReturn(finishedDetails);

        service.pulseStreamingJobs();
        assertEquals(1, published.size());
        assertEquals("RUNNING", MAPPER.readTree(published.get(0)).path("eventType").asText());

        service.pulseStreamingJobs();
        assertEquals(2, published.size());
        JsonNode terminal = MAPPER.readTree(published.get(1));
        assertEquals("COMPLETE", terminal.path("eventType").asText());
        assertEquals("RUNNING", terminal.path("run").path("facets").path("seatunnel")
                .path("prevStatus").asText());
    }

    /**
     * One listing row without a status must not abort the pulse: the remaining instances keep
     * their heartbeats and stay tracked, so their later terminal is still published.
     */
    @Test
    void aMissingStatusDoesNotAbortTheStreamingPulseOrLoseTracking() throws Exception {
        JobInstanceVO missing = instance();
        missing.setId(110L);
        missing.setJobStatus(null);
        missing.setEngineJobId("engine-job-91");
        JobInstanceVO running = instance();
        running.setId(111L);
        running.setJobStatus("RUNNING");
        running.setEngineJobId("engine-job-92");
        JobInstanceVO failed = instance();
        failed.setId(111L);
        failed.setJobStatus("FAILED");
        failed.setEngineJobId("engine-job-92");
        when(streamingJobInstanceDao.listRunning())
                .thenReturn(List.of(missing, running))
                .thenReturn(List.of());
        when(streamingJobInstanceDao.selectDetailById(111L))
                .thenReturn(running)
                .thenReturn(failed);

        service.pulseStreamingJobs();
        service.pulseStreamingJobs();

        assertEquals(2, published.size());
        assertEquals("RUNNING", MAPPER.readTree(published.get(0)).path("eventType").asText());
        assertEquals("FAIL", MAPPER.readTree(published.get(1)).path("eventType").asText());
    }
}
