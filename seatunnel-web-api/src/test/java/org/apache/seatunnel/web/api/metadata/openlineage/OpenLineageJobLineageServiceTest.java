package org.apache.seatunnel.web.api.metadata.openlineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.alarm.event.JobStatusChangedEvent;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.JobInstance;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
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

    /** Every JSON the service hands to the bridge, in publish order. */
    private List<String> published;

    private OpenLineageJobLineageService service;

    @BeforeEach
    void setUp() {
        published = new ArrayList<>();
        service = new OpenLineageJobLineageService(
                properties, bridge, jobInstanceDao, streamingJobInstanceDao, dataSourceDao);
        LineageBridgeProperties.Kafka kafka = new LineageBridgeProperties.Kafka();
        kafka.setEnabled(true);
        kafka.setBootstrapServers("kafka:9092");
        kafka.setSourceTopic("meta_lineage_topic");
        lenient().when(properties.getKafka()).thenReturn(kafka);
        lenient().doAnswer(invocation -> {
            published.add(invocation.getArgument(1));
            return true;
        }).when(bridge).publish(anyString(), anyString());
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
            attempts.add(invocation.getArgument(1));
            return deliveries.getAndIncrement() == 0 ? false : true;
        }).when(bridge).publish(anyString(), anyString());

        // Pulse 1 tracks the running instance, pulse 2 sees the bulk failure and reports it.
        service.pulseBatchJobs();
        service.pulseBatchJobs();

        // The undelivered terminal is not forgotten: the next pulse publishes it again.
        assertEquals(1, attempts.size());
        service.pulseBatchJobs();
        assertEquals(2, attempts.size());
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
}
