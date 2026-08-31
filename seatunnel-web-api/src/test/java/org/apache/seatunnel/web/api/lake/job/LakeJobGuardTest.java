package org.apache.seatunnel.web.api.lake.job;

import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.core.hocon.JobDefinitionCommandResolver;
import org.apache.seatunnel.web.core.hocon.StreamingJobDefinitionCommandResolver;
import org.apache.seatunnel.web.core.job.handler.script.ScriptJobDefinitionParser;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeJobGuardTest {

    private static final long BINDING_ID = 7L;
    private static final long LAKE_DATA_SOURCE_ID = 99L;

    private LakeOdsTableMappingDao tableMappingDao;
    private LakeJobRelationDao relationDao;
    private JobDefinitionCommandResolver batchCommandResolver;
    private StreamingJobDefinitionCommandResolver streamingCommandResolver;
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

        relationDao = mock(LakeJobRelationDao.class);
        batchCommandResolver = mock(JobDefinitionCommandResolver.class);
        streamingCommandResolver = mock(StreamingJobDefinitionCommandResolver.class);

        guard = new LakeJobGuard(
                properties,
                dataSourceDao,
                bindingDao,
                tableMappingDao,
                new ScriptJobDefinitionParser(),
                relationDao,
                batchCommandResolver,
                streamingCommandResolver);
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
    void saveAllowsChangingExistingTableRelationToAnotherManagedMapping() {
        BatchGuideSingleJobSaveCommand command = new BatchGuideSingleJobSaveCommand();
        command.setId(404L);
        command.setOdsDatabaseBindingId(BINDING_ID);
        Map<String, Object> workflow = singleWorkflow("ERROR_WHEN_SCHEMA_NOT_EXIST");
        sinkConfig(workflow).put("targetTableName", "ods_customers");
        command.setWorkflow(workflow);

        when(relationDao.queryActiveByJobId(404L))
                .thenReturn(List.of(activeTableRelation(404L, 70L)));
        when(tableMappingDao.queryByBindingIdAndTargetTable(BINDING_ID, "ods_customers"))
                .thenReturn(managedMapping(71L));

        assertDoesNotThrow(() -> guard.validateBeforeSave(command));
    }

    @Test
    void saveAllowsChangingExistingTableRelationToNamespace() {
        BatchGuideMultiJobSaveCommand command = new BatchGuideMultiJobSaveCommand();
        command.setId(405L);
        command.setOdsDatabaseBindingId(BINDING_ID);
        command.setContent(multiContent("ERROR_WHEN_SCHEMA_NOT_EXIST", "4"));

        when(relationDao.queryActiveByJobId(405L))
                .thenReturn(List.of(activeTableRelation(405L, 70L)));

        assertDoesNotThrow(() -> guard.validateBeforeSave(command));
    }

    @Test
    void onlineAndExecuteRejectPersistedRelationMismatch() {
        BatchGuideSingleJobSaveCommand persisted = new BatchGuideSingleJobSaveCommand();
        persisted.setId(406L);
        persisted.setOdsDatabaseBindingId(BINDING_ID);
        Map<String, Object> workflow = singleWorkflow("ERROR_WHEN_SCHEMA_NOT_EXIST");
        sinkConfig(workflow).put("targetTableName", "ods_customers");
        persisted.setWorkflow(workflow);

        when(batchCommandResolver.resolve(406L)).thenReturn(persisted);
        when(relationDao.queryActiveByJobId(406L))
                .thenReturn(List.of(activeTableRelation(406L, 70L)));
        when(tableMappingDao.queryByBindingIdAndTargetTable(BINDING_ID, "ods_customers"))
                .thenReturn(managedMapping(71L));

        assertLakeRequestInvalid(
                () -> guard.validateBeforeOnline(406L, LakeJobRuntimeType.BATCH));
        assertLakeRequestInvalid(
                () -> guard.validateBeforeExecute(406L, LakeJobRuntimeType.BATCH));
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

    @Test
    void onlineReloadsPersistedBatchCommandAndRestoresLegacyBinding() {
        BatchGuideSingleJobSaveCommand legacy = new BatchGuideSingleJobSaveCommand();
        legacy.setId(101L);
        legacy.setWorkflow(singleWorkflowWithoutBinding());

        LakeJobRelation relation = new LakeJobRelation();
        relation.setJobId(101L);
        relation.setOdsDatabaseBindingId(BINDING_ID);
        relation.setTableMappingId(70L);
        relation.setRelationScope(LakeRelationScope.TABLE);
        relation.setRelationStatus(LakeRelationStatus.ACTIVE);

        when(batchCommandResolver.resolve(101L)).thenReturn(legacy);
        when(relationDao.queryActiveByJobId(101L)).thenReturn(List.of(relation));
        when(tableMappingDao.queryByIdIncludingDeleted(70L)).thenReturn(managedMapping());

        guard.validateBeforeOnline(101L, LakeJobRuntimeType.BATCH);

        assertEquals(BINDING_ID, legacy.getOdsDatabaseBindingId());
        assertEquals("ERROR_WHEN_SCHEMA_NOT_EXIST", sinkConfig(legacy.getWorkflow()).get("schemaSaveMode"));
        verify(batchCommandResolver).resolve(101L);
    }

    @Test
    void executeRejectsDeletedHistoricalMappingInsteadOfRecreatingIt() {
        BatchGuideSingleJobSaveCommand persisted = new BatchGuideSingleJobSaveCommand();
        persisted.setId(202L);
        persisted.setOdsDatabaseBindingId(BINDING_ID);
        persisted.setWorkflow(singleWorkflow("CREATE_SCHEMA_WHEN_NOT_EXIST"));

        LakeOdsTableMapping deleted = managedMapping();
        deleted.setDeleted(true);
        when(batchCommandResolver.resolve(202L)).thenReturn(persisted);
        when(tableMappingDao.queryByBindingIdAndTargetTable(BINDING_ID, "ods_orders"))
                .thenReturn(null);
        when(tableMappingDao.queryByBindingIdAndTargetTableIncludingDeleted(BINDING_ID, "ods_orders"))
                .thenReturn(deleted);

        assertLakeRequestInvalid(
                () -> guard.validateBeforeExecute(202L, LakeJobRuntimeType.BATCH));
        verify(tableMappingDao)
                .queryByBindingIdAndTargetTableIncludingDeleted(BINDING_ID, "ods_orders");
    }

    @Test
    void executeReloadsLatestStreamingCommandThroughTheStreamingResolver() {
        StreamingGuideMultiJobSaveCommand persisted = new StreamingGuideMultiJobSaveCommand();
        persisted.setId(303L);
        persisted.setContent(multiContent("ERROR_WHEN_SCHEMA_NOT_EXIST", "4"));

        when(streamingCommandResolver.resolve(303L)).thenReturn(persisted);

        guard.validateBeforeExecute(303L, LakeJobRuntimeType.STREAMING);

        verify(streamingCommandResolver).resolve(303L);
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
        return managedMapping(70L);
    }

    private LakeOdsTableMapping managedMapping(long mappingId) {
        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.setId(mappingId);
        mapping.setOdsDatabaseBindingId(BINDING_ID);
        mapping.setManagementLevel(LakeManagementLevel.MANAGED);
        mapping.setDeleted(false);
        return mapping;
    }

    private LakeJobRelation activeTableRelation(long jobId, long mappingId) {
        LakeJobRelation relation = new LakeJobRelation();
        relation.setJobId(jobId);
        relation.setOdsDatabaseBindingId(BINDING_ID);
        relation.setTableMappingId(mappingId);
        relation.setRelationScope(LakeRelationScope.TABLE);
        relation.setRelationStatus(LakeRelationStatus.ACTIVE);
        return relation;
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
