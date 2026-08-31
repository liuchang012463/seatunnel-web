package org.apache.seatunnel.web.api.lake.job;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideMultiJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleIncrementalJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchScriptJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideSingleJobSaveCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LakeExactSingleProjectionPlannerTest {

    private static final long BINDING_ID = 7L;
    private static final long SOURCE_DATA_SOURCE_ID = 10L;
    private static final long LAKE_DATA_SOURCE_ID = 99L;

    private LakeOdsDatabaseBindingDao bindingDao;
    private LakeOdsTableMappingDao mappingDao;
    private LakeDorisClientProvider provider;
    private DorisLakeClient doris;
    private LakeExactSingleProjectionPlanner planner;

    @BeforeEach
    void setUp() {
        bindingDao = mock(LakeOdsDatabaseBindingDao.class);
        mappingDao = mock(LakeOdsTableMappingDao.class);
        provider = mock(LakeDorisClientProvider.class);
        doris = mock(DorisLakeClient.class);

        LakeProperties properties = new LakeProperties();
        properties.setDataSourceId(LAKE_DATA_SOURCE_ID);
        planner = new LakeExactSingleProjectionPlanner(
                bindingDao, mappingDao, provider, properties);

        when(bindingDao.queryActiveById(BINDING_ID)).thenReturn(readyBinding());
        when(provider.get(LAKE_DATA_SOURCE_ID)).thenReturn(doris);
        when(doris.tableExists("ods_demo", "ods_orders")).thenReturn(false);
    }

    @Test
    void classifiesActiveMappingsAndDoesNotReadHistoryAfterActiveHit() {
        for (LakeManagementLevel managementLevel : LakeManagementLevel.values()) {
            LakeOdsTableMapping mapping = mapping(managementLevel);
            when(mappingDao.queryByBindingIdAndTargetTable(BINDING_ID, "ods_orders"))
                    .thenReturn(mapping);

            LakeExactSingleProjectionPlanner.ProjectionPlan plan = planner.plan(singleCommand());

            LakeExactSingleProjectionPlanner.Decision expected = switch (managementLevel) {
                case MANAGED -> LakeExactSingleProjectionPlanner.Decision.REUSE_MANAGED;
                case AUTO_CREATED -> LakeExactSingleProjectionPlanner.Decision.REUSE_AUTO_CREATED;
                case UNMANAGED -> LakeExactSingleProjectionPlanner.Decision.REUSE_UNMANAGED;
            };
            assertEquals(expected, plan.decision());
            assertFalse(plan.isUnknown());
            verify(mappingDao, never()).queryByBindingIdAndTargetTableIncludingDeleted(
                    BINDING_ID, "ods_orders");

            when(mappingDao.queryByBindingIdAndTargetTable(BINDING_ID, "ods_orders"))
                    .thenReturn(null);
        }
        verify(doris, times(LakeManagementLevel.values().length)).tableExists(
                "ods_demo", "ods_orders");
    }

    @Test
    void classifiesMissingTableAsPendingAutoCreation() {
        LakeExactSingleProjectionPlanner.ProjectionPlan plan = planner.plan(singleCommand());

        assertEquals(
                LakeExactSingleProjectionPlanner.Decision.CREATE_AUTO_PENDING,
                plan.decision());
        assertEquals(Boolean.FALSE, plan.actualTableExists());
        verify(mappingDao).queryByBindingIdAndTargetTable(BINDING_ID, "ods_orders");
        verify(mappingDao).queryByBindingIdAndTargetTableIncludingDeleted(BINDING_ID, "ods_orders");
        verify(bindingDao).queryActiveById(BINDING_ID);
        verify(provider).get(LAKE_DATA_SOURCE_ID);
        verify(doris).tableExists("ods_demo", "ods_orders");
        // No DAO insert/update and no Doris DDL are part of planning.
        verifyNoMoreInteractions(bindingDao, mappingDao, provider, doris);
    }

    @Test
    void classifiesExistingUnmappedTableAsUnmanagedReady() {
        when(doris.tableExists("ods_demo", "ods_orders")).thenReturn(true);

        LakeExactSingleProjectionPlanner.ProjectionPlan plan = planner.plan(singleCommand());

        assertEquals(
                LakeExactSingleProjectionPlanner.Decision.CREATE_UNMANAGED_READY,
                plan.decision());
        assertEquals(Boolean.TRUE, plan.actualTableExists());
        assertNull(plan.existingMapping());
    }

    @Test
    void rejectsDeletedTombstoneBeforeDorisOrAnyWrite() {
        LakeOdsTableMapping deleted = mapping(LakeManagementLevel.AUTO_CREATED);
        deleted.setDeleted(true);
        when(mappingDao.queryByBindingIdAndTargetTableIncludingDeleted(BINDING_ID, "ods_orders"))
                .thenReturn(deleted);

        LakeExactSingleProjectionPlanner.ProjectionPlan plan = planner.plan(singleCommand());

        assertEquals(LakeExactSingleProjectionPlanner.Decision.REJECT, plan.decision());
        assertEquals(LakeErrorCode.LAKE_REQUEST_INVALID, plan.failureCode());
        verify(provider, never()).get(eq(LAKE_DATA_SOURCE_ID));
        verifyNoMoreInteractions(doris);
        verify(mappingDao, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(mappingDao, never()).updateById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void returnsStableUnknownWhenDorisMetadataReadFails() {
        when(doris.tableExists("ods_demo", "ods_orders"))
                .thenThrow(new IllegalStateException("jdbc password=must-not-escape"));

        LakeExactSingleProjectionPlanner.ProjectionPlan plan = planner.plan(singleCommand());

        assertEquals(LakeExactSingleProjectionPlanner.Decision.UNKNOWN, plan.decision());
        assertEquals(LakeErrorCode.LAKE_DORIS_UNAVAILABLE, plan.failureCode());
        assertEquals("lake Doris table state is unavailable", plan.failureReason());
        assertFalse(plan.failureReason().contains("must-not-escape"));
        verify(doris).tableExists("ods_demo", "ods_orders");
        verifyNoMoreInteractions(doris);
    }

    @Test
    void rejectsNonExactSourceEndpointWithoutConsultingState() {
        BatchGuideSingleJobSaveCommand command = singleCommand();
        sourceConfig(command).put("query", "select * from orders");

        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> planner.plan(command));

        assertEquals(LakeErrorCode.LAKE_REQUEST_INVALID, exception.getLakeErrorCode());
        assertNull(exception.getCause());
        verifyNoMoreInteractions(bindingDao, mappingDao, provider, doris);
    }

    @Test
    void rejectsDangerousAndUnsupportedSchemaModes() {
        for (String mode : List.of("RECREATE_SCHEMA", "DROP_AND_CREATE", "UPDATE_SCHEMA")) {
            BatchGuideSingleJobSaveCommand command = singleCommand();
            sinkConfig(command).put("schemaSaveMode", mode);

            LakeServiceException exception = assertThrows(
                    LakeServiceException.class, () -> planner.plan(command));
            assertEquals(LakeErrorCode.LAKE_REQUEST_INVALID, exception.getLakeErrorCode());
        }
        verifyNoMoreInteractions(bindingDao, mappingDao, provider, doris);
    }

    @Test
    void ordinaryAndMultiJobsAreNotApplicable() {
        BatchScriptJobSaveCommand script = new BatchScriptJobSaveCommand();
        assertEquals(
                LakeExactSingleProjectionPlanner.Decision.NOT_APPLICABLE,
                planner.plan(script).decision());

        BatchGuideMultiJobSaveCommand multi = new BatchGuideMultiJobSaveCommand();
        assertEquals(
                LakeExactSingleProjectionPlanner.Decision.NOT_APPLICABLE,
                planner.plan(multi).decision());
        verifyNoInteractions(bindingDao, mappingDao, provider, doris);
    }

    @Test
    void supportsIncrementalAndStreamingSingleCommands() {
        BatchGuideSingleIncrementalJobSaveCommand incremental =
                new BatchGuideSingleIncrementalJobSaveCommand();
        incremental.setOdsDatabaseBindingId(BINDING_ID);
        incremental.setWorkflow(workflow());
        assertEquals(
                LakeExactSingleProjectionPlanner.Decision.CREATE_AUTO_PENDING,
                planner.plan(incremental).decision());

        StreamingGuideSingleJobSaveCommand streaming = new StreamingGuideSingleJobSaveCommand();
        streaming.setOdsDatabaseBindingId(BINDING_ID);
        streaming.setWorkflow(workflow());
        assertEquals(
                LakeExactSingleProjectionPlanner.Decision.CREATE_AUTO_PENDING,
                planner.plan(streaming).decision());
        assertEquals(
                org.apache.seatunnel.web.common.enums.LakeJobRuntimeType.STREAMING,
                planner.plan(streaming).runtimeType());
    }

    private BatchGuideSingleJobSaveCommand singleCommand() {
        BatchGuideSingleJobSaveCommand command = new BatchGuideSingleJobSaveCommand();
        command.setOdsDatabaseBindingId(BINDING_ID);
        command.setWorkflow(workflow());
        return command;
    }

    private Map<String, Object> workflow() {
        Map<String, Object> source = new HashMap<>(Map.of(
                "dataSourceId", String.valueOf(SOURCE_DATA_SOURCE_ID),
                "dbType", "MYSQL",
                "pluginName", "MYSQL",
                "table_path", "orders"));
        Map<String, Object> sink = new HashMap<>(Map.of(
                "dataSourceId", String.valueOf(LAKE_DATA_SOURCE_ID),
                "dbType", "DORIS",
                "pluginName", "DORIS",
                "targetTableName", "ods_orders",
                "schemaSaveMode", "CREATE_SCHEMA_WHEN_NOT_EXIST"));
        return new HashMap<>(Map.of(
                "nodes", List.of(node("source", source), node("sink", sink))));
    }

    private Map<String, Object> node(String type, Map<String, Object> config) {
        return new HashMap<>(Map.of(
                "id", type,
                "data", new HashMap<>(Map.of(
                        "nodeType", type,
                        "config", config))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sourceConfig(BatchGuideSingleJobSaveCommand command) {
        return config(command, 0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sinkConfig(BatchGuideSingleJobSaveCommand command) {
        return config(command, 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> config(BatchGuideSingleJobSaveCommand command, int index) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) command.getWorkflow().get("nodes");
        Map<String, Object> node = nodes.get(index);
        Map<String, Object> data = (Map<String, Object>) node.get("data");
        return (Map<String, Object>) data.get("config");
    }

    private LakeOdsDatabaseBinding readyBinding() {
        LakeOdsDatabaseBinding binding = new LakeOdsDatabaseBinding();
        binding.setId(BINDING_ID);
        binding.setLakeDataSourceId(LAKE_DATA_SOURCE_ID);
        binding.setSourceDataSourceId(SOURCE_DATA_SOURCE_ID);
        binding.setDatabaseName("ods_demo");
        binding.setResourceStatus(LakeResourceStatus.READY);
        binding.setDeleted(false);
        return binding;
    }

    private LakeOdsTableMapping mapping(LakeManagementLevel managementLevel) {
        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.setId(70L);
        mapping.setOdsDatabaseBindingId(BINDING_ID);
        mapping.setLakeDataSourceId(LAKE_DATA_SOURCE_ID);
        mapping.setDatabaseName("ods_demo");
        mapping.setTargetTableName("ods_orders");
        mapping.setManagementLevel(managementLevel);
        mapping.setDeleted(false);
        return mapping;
    }
}
