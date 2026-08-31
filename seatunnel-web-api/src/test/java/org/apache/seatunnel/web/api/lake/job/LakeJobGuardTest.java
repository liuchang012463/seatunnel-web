package org.apache.seatunnel.web.api.lake.job;

import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.core.job.handler.script.ScriptJobDefinitionParser;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideMultiJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleIncrementalJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchScriptJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;
import org.apache.seatunnel.web.spi.bean.dto.config.ScriptJobContent;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideMultiJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideSingleJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingScriptJobSaveCommand;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LakeJobGuardTest {

    private static final long BINDING_ID = 7L;
    private static final long LAKE_DATA_SOURCE_ID = 99L;

    private LakeOdsTableMappingDao tableMappingDao;
    private LakeJobGuard guard;

    @BeforeEach
    void setUp() {
        LakeProperties properties = new LakeProperties();
        properties.setEnabled(true);
        properties.setDataSourceId(LAKE_DATA_SOURCE_ID);

        DataSourceDao dataSourceDao = mock(DataSourceDao.class);
        DataSource lakeDataSource = new DataSource();
        lakeDataSource.setId(LAKE_DATA_SOURCE_ID);
        lakeDataSource.setDbType(DbType.DORIS);
        when(dataSourceDao.queryById(LAKE_DATA_SOURCE_ID)).thenReturn(lakeDataSource);

        LakeOdsDatabaseBindingDao bindingDao = mock(LakeOdsDatabaseBindingDao.class);
        when(bindingDao.queryActiveById(BINDING_ID)).thenReturn(readyBinding());

        tableMappingDao = mock(LakeOdsTableMappingDao.class);
        when(tableMappingDao.queryByBindingIdAndTargetTable(eq(BINDING_ID), eq("ods_orders")))
                .thenReturn(managedMapping());

        guard = new LakeJobGuard(
                properties,
                dataSourceDao,
                bindingDao,
                tableMappingDao,
                new ScriptJobDefinitionParser());
    }

    @Test
    void forcesManagedSchemaModeForBatchAndStreamingSingleJobs() {
        BatchGuideSingleJobSaveCommand batch = new BatchGuideSingleJobSaveCommand();
        batch.setOdsDatabaseBindingId(BINDING_ID);
        Map<String, Object> batchWorkflow = singleWorkflow("CREATE_SCHEMA_WHEN_NOT_EXIST");
        batch.setWorkflow(batchWorkflow);

        guard.validateBeforeSave(batch);

        assertEquals("ERROR_WHEN_SCHEMA_NOT_EXIST", sinkConfig(batchWorkflow).get("schemaSaveMode"));
        assertEquals("ERROR_WHEN_SCHEMA_NOT_EXIST", sinkConfig(batchWorkflow).get("schema_save_mode"));

        StreamingGuideSingleJobSaveCommand streaming = new StreamingGuideSingleJobSaveCommand();
        streaming.setOdsDatabaseBindingId(BINDING_ID);
        Map<String, Object> streamingWorkflow = singleWorkflow("CREATE_SCHEMA_WHEN_NOT_EXIST");
        streaming.setWorkflow(streamingWorkflow);

        guard.validateBeforeSave(streaming);

        assertEquals("ERROR_WHEN_SCHEMA_NOT_EXIST", sinkConfig(streamingWorkflow).get("schemaSaveMode"));
    }

    @Test
    void rejectsRecreateForIncrementalAndMultiWholeOdsJobs() {
        BatchGuideSingleIncrementalJobSaveCommand incremental =
                new BatchGuideSingleIncrementalJobSaveCommand();
        incremental.setOdsDatabaseBindingId(BINDING_ID);
        incremental.setWorkflow(singleWorkflow("RECREATE_SCHEMA"));
        assertLakeRequestInvalid(() -> guard.validateBeforeSave(incremental));

        BatchGuideMultiJobSaveCommand multi = new BatchGuideMultiJobSaveCommand();
        multi.setOdsDatabaseBindingId(BINDING_ID);
        multi.setContent(multiContent("RECREATE_SCHEMA", "3"));
        assertLakeRequestInvalid(() -> guard.validateBeforeSave(multi));

        StreamingGuideMultiJobSaveCommand whole = new StreamingGuideMultiJobSaveCommand();
        whole.setOdsDatabaseBindingId(BINDING_ID);
        whole.setContent(multiContent("DROP_AND_CREATE", "4"));
        assertLakeRequestInvalid(() -> guard.validateBeforeSave(whole));
    }

    @Test
    void requiresBindingInBothDirectionsAndChecksSourceOwner() {
        BatchGuideSingleJobSaveCommand missingBinding = new BatchGuideSingleJobSaveCommand();
        missingBinding.setWorkflow(singleWorkflowWithoutBinding());
        assertLakeRequestInvalid(() -> guard.validateBeforeSave(missingBinding));

        BatchGuideSingleJobSaveCommand wrongOwner = new BatchGuideSingleJobSaveCommand();
        wrongOwner.setOdsDatabaseBindingId(BINDING_ID);
        Map<String, Object> workflow = singleWorkflow("ERROR_WHEN_SCHEMA_NOT_EXIST");
        sourceConfig(workflow).put("dataSourceId", "11");
        wrongOwner.setWorkflow(workflow);
        assertLakeRequestInvalid(() -> guard.validateBeforeSave(wrongOwner));

        BatchGuideSingleJobSaveCommand mismatch = new BatchGuideSingleJobSaveCommand();
        mismatch.setOdsDatabaseBindingId(BINDING_ID);
        Map<String, Object> mismatchWorkflow = singleWorkflow("ERROR_WHEN_SCHEMA_NOT_EXIST");
        sinkConfig(mismatchWorkflow).put("odsDatabaseBindingId", 8L);
        mismatch.setWorkflow(mismatchWorkflow);
        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> guard.validateBeforeSave(mismatch));
        assertEquals(LakeErrorCode.LAKE_REQUEST_INVALID, exception.getLakeErrorCode());
        assertNull(exception.getCause());
    }

    @Test
    void acceptsOrdinaryStructuredJobsWithoutLakeSink() {
        BatchGuideMultiJobSaveCommand ordinary = new BatchGuideMultiJobSaveCommand();
        ordinary.setContent(multiContent("CREATE_SCHEMA_WHEN_NOT_EXIST", "4"));
        ordinary.getContent().getTarget().setDatasourceId("100");
        ordinary.getContent().getTarget().setDbType("MYSQL");
        ordinary.getContent().getTarget().setPluginName("MYSQL");
        ordinary.getContent().getTarget().setOdsDatabaseBindingId(null);

        assertDoesNotThrow(() -> guard.validateBeforeSave(ordinary));
    }

    @Test
    void rejectsLakeDataSourceInBatchAndStreamingScripts() {
        BatchScriptJobSaveCommand batch = new BatchScriptJobSaveCommand();
        batch.setContent(scriptContent("99"));
        assertLakeRequestInvalid(() -> guard.validateBeforeSave(batch));

        StreamingScriptJobSaveCommand streaming = new StreamingScriptJobSaveCommand();
        streaming.setContent(scriptContent("99"));
        assertLakeRequestInvalid(() -> guard.validateBeforeSave(streaming));

        BatchScriptJobSaveCommand ordinary = new BatchScriptJobSaveCommand();
        ordinary.setContent(scriptContent("100"));
        assertDoesNotThrow(() -> guard.validateBeforeSave(ordinary));
    }

    private void assertLakeRequestInvalid(Runnable action) {
        LakeServiceException exception = assertThrows(LakeServiceException.class, action::run);
        assertEquals(LakeErrorCode.LAKE_REQUEST_INVALID, exception.getLakeErrorCode());
        assertEquals("lake job safety validation failed", exception.getMessage());
        assertNull(exception.getCause());
    }

    private LakeOdsDatabaseBinding readyBinding() {
        LakeOdsDatabaseBinding binding = new LakeOdsDatabaseBinding();
        binding.setId(BINDING_ID);
        binding.setLakeDataSourceId(LAKE_DATA_SOURCE_ID);
        binding.setSourceDataSourceId(10L);
        binding.setDatabaseName("ods_demo");
        binding.setResourceStatus(LakeResourceStatus.READY);
        binding.setDeleted(false);
        return binding;
    }

    private LakeOdsTableMapping managedMapping() {
        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.setId(70L);
        mapping.setOdsDatabaseBindingId(BINDING_ID);
        mapping.setManagementLevel(LakeManagementLevel.MANAGED);
        mapping.setDeleted(false);
        return mapping;
    }

    private Map<String, Object> singleWorkflow(String schemaSaveMode) {
        Map<String, Object> source = new HashMap<>(Map.of(
                "dataSourceId", "10",
                "dbType", "MYSQL",
                "pluginName", "MYSQL",
                "table", "orders"));
        Map<String, Object> sink = new HashMap<>(Map.of(
                "dataSourceId", String.valueOf(LAKE_DATA_SOURCE_ID),
                "dbType", "DORIS",
                "pluginName", "DORIS",
                "targetTableName", "ods_orders",
                "schemaSaveMode", schemaSaveMode,
                "odsDatabaseBindingId", BINDING_ID));
        return new HashMap<>(Map.of(
                "nodes", List.of(node("source", source), node("sink", sink)),
                "edges", List.of(Map.of("source", "source", "target", "sink"))));
    }

    private Map<String, Object> singleWorkflowWithoutBinding() {
        Map<String, Object> workflow = singleWorkflow("ERROR_WHEN_SCHEMA_NOT_EXIST");
        sinkConfig(workflow).remove("odsDatabaseBindingId");
        return workflow;
    }

    private Map<String, Object> node(String type, Map<String, Object> config) {
        return new HashMap<>(Map.of(
                "id", type,
                "data", new HashMap<>(Map.of(
                        "nodeType", type,
                        "config", config))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sinkConfig(Map<String, Object> workflow) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) workflow.get("nodes");
        Map<String, Object> sink = nodes.get(1);
        Map<String, Object> data = (Map<String, Object>) sink.get("data");
        return (Map<String, Object>) data.get("config");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sourceConfig(Map<String, Object> workflow) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) workflow.get("nodes");
        Map<String, Object> source = nodes.get(0);
        Map<String, Object> data = (Map<String, Object>) source.get("data");
        return (Map<String, Object>) data.get("config");
    }

    private GuideMultiJobContent multiContent(String schemaSaveMode, String matchMode) {
        GuideMultiJobContent content = new GuideMultiJobContent();
        GuideMultiJobContent.WorkflowSourceConfig source =
                new GuideMultiJobContent.WorkflowSourceConfig();
        source.setDatasourceId("10");
        source.setDbType("MYSQL");
        source.setPluginName("MYSQL");
        content.setSource(source);

        GuideMultiJobContent.WorkflowTargetConfig target =
                new GuideMultiJobContent.WorkflowTargetConfig();
        target.setDatasourceId(String.valueOf(LAKE_DATA_SOURCE_ID));
        target.setDbType("DORIS");
        target.setPluginName("DORIS");
        target.setConnectorType("Doris");
        target.setSchemaSaveMode(schemaSaveMode);
        target.setOdsDatabaseBindingId(BINDING_ID);
        content.setTarget(target);

        GuideMultiJobContent.TableMatchConfig tableMatch =
                new GuideMultiJobContent.TableMatchConfig();
        tableMatch.setMode(matchMode);
        tableMatch.setTables(List.of("orders"));
        content.setTableMatch(tableMatch);
        return content;
    }

    private ScriptJobContent scriptContent(String dataSourceId) {
        ScriptJobContent content = new ScriptJobContent();
        content.setScriptType("HOCON");
        content.setHoconContent("source { Jdbc { dataSourceId = 10 } }\n"
                + "sink { Doris { dataSourceId = " + dataSourceId + " } }");
        return content;
    }
}
