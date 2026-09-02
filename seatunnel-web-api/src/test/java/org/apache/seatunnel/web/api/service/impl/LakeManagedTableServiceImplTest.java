package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.contract.DorisTypeBase;
import org.apache.seatunnel.web.api.lake.contract.TargetColumn;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.api.lake.contract.TargetContractCanonicalizer;
import org.apache.seatunnel.web.api.lake.contract.TargetDistribution;
import org.apache.seatunnel.web.api.lake.contract.TargetPartition;
import org.apache.seatunnel.web.api.lake.contract.TargetType;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationExecution;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationHandle;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationIntent;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceOperationCoordinator;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceTypes;
import org.apache.seatunnel.web.api.lake.source.LakeSourceObjectResolver;
import org.apache.seatunnel.web.api.lake.source.SourceColumnSnapshot;
import org.apache.seatunnel.web.api.lake.source.SourceConstraintSnapshot;
import org.apache.seatunnel.web.api.lake.source.SourceObjectSnapshot;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTableContractFactory;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTableDeleteImpactVO;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTableRelationImpactVO;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTablePreviewVO;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTableVO;
import org.apache.seatunnel.web.api.lake.table.LakePreviewTokenService;
import org.apache.seatunnel.web.api.lake.table.LakeTableDriftEvaluator;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.common.enums.ReleaseState;
import org.apache.seatunnel.web.common.enums.ScheduleStatusEnum;
import org.apache.seatunnel.web.common.enums.LakeTableModel;
import org.apache.seatunnel.web.dao.entity.JobDefinitionEntity;
import org.apache.seatunnel.web.dao.entity.JobSchedule;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeSourceObjectRef;
import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;
import org.apache.seatunnel.web.dao.entity.LakeLifecyclePolicy;
import org.apache.seatunnel.web.dao.entity.StreamingJobDefinitionEntity;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.JobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.JobScheduleDao;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeSourceObjectRefDao;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeLifecyclePolicyDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTableCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTableColumnDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTableDeleteDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTablePartitionDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTablePreviewDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LakeManagedTableServiceImplTest {

    @Mock private DataSourceDao dataSourceDao;
    @Mock private LakeOdsDatabaseBindingDao databaseBindingDao;
    @Mock private LakeOdsTableMappingDao tableMappingDao;
    @Mock private LakeSourceObjectRefDao sourceObjectRefDao;
    @Mock private LakeJobRelationDao jobRelationDao;
    @Mock private JobDefinitionDao batchJobDefinitionDao;
    @Mock private StreamingJobDefinitionDao streamingJobDefinitionDao;
    @Mock private JobScheduleDao jobScheduleDao;
    @Mock private JobInstanceDao jobInstanceDao;
    @Mock private StreamingJobInstanceDao streamingJobInstanceDao;
    @Mock private LakeTableLifecycleBindingDao lifecycleBindingDao;
    @Mock private LakeLifecyclePolicyDao lifecyclePolicyDao;
    @Mock private LakeSourceObjectResolver sourceResolver;
    @Mock private LakeDorisClientProvider dorisClientProvider;
    @Mock private LakeResourceOperationCoordinator coordinator;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private DorisLakeClient dorisClient;
    @Mock private LakeTableDriftEvaluator driftEvaluator;
    @Mock private LakeTableReconcilePersistenceService reconcilePersistenceService;

    private LakeProperties properties;
    private LakePreviewTokenService tokenService;
    private LakeManagedTableContractFactory contractFactory;
    private LakeManagedTableServiceImpl service;
    private LakeOdsDatabaseBinding binding;
    private SourceObjectSnapshot source;

    @BeforeEach
    void setUp() {
        properties = new LakeProperties();
        properties.setEnabled(true);
        properties.setPreviewTokenTtl(java.time.Duration.ofMinutes(5));
        tokenService = new LakePreviewTokenService(properties,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), "test-secret");
        contractFactory = new LakeManagedTableContractFactory();
        binding = binding(21L);
        source = source("source-hash");
        service = new LakeManagedTableServiceImpl(
                dataSourceDao, databaseBindingDao, tableMappingDao, sourceObjectRefDao,
                jobRelationDao, lifecycleBindingDao, sourceResolver, dorisClientProvider,
                coordinator, currentUserProvider, tokenService, properties,
                contractFactory, new org.apache.seatunnel.web.api.lake.doris.DorisDdlBuilder(),
                batchJobDefinitionDao, streamingJobDefinitionDao, jobScheduleDao,
                jobInstanceDao, streamingJobInstanceDao, driftEvaluator,
                reconcilePersistenceService, lifecyclePolicyDao);

        lenient().when(currentUserProvider.getCurrentUserId()).thenReturn(7);
        lenient().when(databaseBindingDao.queryActiveById(21L)).thenReturn(binding);
        lenient().when(sourceResolver.resolve(11L, "om-table")).thenReturn(source);
        lenient().when(dorisClientProvider.get(31L)).thenReturn(dorisClient);
        lenient().when(dorisClient.tableExists(anyString(), anyString())).thenReturn(false);
        lenient().when(sourceObjectRefDao.queryByOmEntityIdIncludingDeleted("om-table"))
                .thenReturn(null);
        lenient().when(tableMappingDao.queryByBindingIdAndTargetTableIncludingDeleted(21L, "orders"))
                .thenReturn(null);
        lenient().when(tableMappingDao.queryByBindingIdAndSourceObjectIncludingDeleted(anyLong(), anyLong()))
                .thenReturn(null);
    }

    @Test
    void previewBuildsServerContractAndBindsAllIdentityFieldsIntoToken() {
        LakeManagedTablePreviewVO result = service.preview(previewRequest());

        assertTrue(result.isValid());
        assertNotNull(result.getDdl());
        assertTrue(result.getDdl().contains("CREATE TABLE"));
        assertNotNull(result.getPreviewToken());
        LakePreviewTokenService.Payload payload = tokenService.verify(result.getPreviewToken(), 7);
        assertEquals(11L, payload.sourceDataSourceId());
        assertEquals("om-table", payload.omEntityId());
        assertEquals(21L, payload.odsDatabaseBindingId());
        assertEquals("orders", payload.targetTableName());
        assertEquals(result.getSourceSchemaHash(), payload.sourceSchemaHash());
        assertEquals(result.getTargetContractHash(), payload.targetContractHash());
        assertEquals(result.getTargetContractHash(),
                TargetContractCanonicalizer.canonicalHash(result.getTargetContract()));
        assertNull(payload.lifecyclePolicyId());
        assertFalse(result.getDdl().contains("partition.retention_count"));
    }

    @Test
    void lifecyclePreviewSignsPolicySnapshotAndEmitsRetentionPropertyInCreateDdl() {
        when(sourceResolver.resolve(11L, "om-table")).thenReturn(lifecycleSource());
        when(lifecyclePolicyDao.queryById(901L)).thenReturn(lifecyclePolicy());

        LakeManagedTablePreviewVO result = service.preview(lifecyclePreviewRequest());

        assertTrue(result.isValid());
        assertTrue(result.getDdl().contains("AUTO PARTITION BY RANGE"));
        assertTrue(result.getDdl().contains("\"partition.retention_count\" = \"7\""));
        LakePreviewTokenService.Payload payload = tokenService.verify(result.getPreviewToken(), 7);
        assertEquals(901L, payload.lifecyclePolicyId());
        assertEquals(3, payload.lifecyclePolicyVersion());
        assertEquals(7, payload.lifecycleRetentionCount());
        assertEquals("event_time", payload.lifecyclePartitionColumn());
        assertEquals("DAY", payload.lifecycleGranularity());
        assertEquals("partition.retention_count", payload.lifecyclePropertyKey());
        assertEquals("7", payload.lifecyclePropertyValue());
        assertTrue(payload.lifecyclePolicySnapshotJson().contains("\"policyId\":901"));
        assertTrue(payload.lifecycleIntentHash() != null && payload.lifecycleIntentHash().length() == 64);
        assertEquals(result.getTargetContractHash(), payload.targetContractHash());
        assertEquals(TargetContractCanonicalizer.canonicalHash(result.getTargetContract()),
                result.getTargetContractHash());
    }

    @Test
    void lifecyclePreviewRejectsPolicyWithoutDateTimeNotNullAutoRangePartition() {
        when(lifecyclePolicyDao.queryById(901L)).thenReturn(lifecyclePolicy());

        LakeManagedTablePreviewVO result = service.preview(previewRequestWithPolicy());

        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("AUTO RANGE"));
        assertNull(result.getPreviewToken());
    }

    @Test
    void lifecyclePreviewRejectsNullableSourcePartitionColumn() {
        when(sourceResolver.resolve(11L, "om-table")).thenReturn(lifecycleSource(true));

        LakeManagedTablePreviewVO result = service.preview(lifecyclePreviewRequest());

        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("NOT NULL"));
        assertNull(result.getPreviewToken());
    }

    @Test
    void lifecycleCreateRejectsStalePolicyBeforeDorisOrTokenConsumption() {
        when(sourceResolver.resolve(11L, "om-table")).thenReturn(lifecycleSource());
        LakeLifecyclePolicy current = lifecyclePolicy();
        LakeLifecyclePolicy stale = lifecyclePolicy();
        stale.setVersion(4);
        when(lifecyclePolicyDao.queryById(901L)).thenReturn(current, stale);
        LakeManagedTablePreviewVO preview = service.preview(lifecyclePreviewRequest());
        clearInvocations(dorisClientProvider, dorisClient);

        LakeManagedTableCreateDTO request = new LakeManagedTableCreateDTO();
        request.setPreviewToken(preview.getPreviewToken());
        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.create(request));

        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, exception.getLakeErrorCode());
        verify(dorisClientProvider, never()).get(anyLong());
        verify(dorisClient, never()).tableExists(anyString(), anyString());
        verify(sourceObjectRefDao, never()).insert(any());
        verify(coordinator, never()).begin(any());
    }

    @Test
    void lifecycleCreateUsesRetentionPropertyInSingleCreateAndNeverAltersAfterward() {
        when(sourceResolver.resolve(11L, "om-table")).thenReturn(lifecycleSource());
        when(lifecyclePolicyDao.queryById(901L)).thenReturn(lifecyclePolicy());
        LakeManagedTablePreviewVO preview = service.preview(lifecyclePreviewRequest());
        AtomicReference<LakeSourceObjectRef> storedSource = new AtomicReference<>();
        AtomicReference<LakeOdsTableMapping> storedMapping = new AtomicReference<>();
        when(sourceObjectRefDao.insert(any(LakeSourceObjectRef.class))).thenAnswer(invocation -> {
            LakeSourceObjectRef reference = invocation.getArgument(0);
            reference.setId(401L);
            storedSource.set(reference);
            return 1;
        });
        when(sourceObjectRefDao.queryByIdIncludingDeleted(401L))
                .thenAnswer(invocation -> storedSource.get());
        when(tableMappingDao.insert(any(LakeOdsTableMapping.class))).thenAnswer(invocation -> {
            LakeOdsTableMapping mapping = invocation.getArgument(0);
            mapping.setId(501L);
            storedMapping.set(mapping);
            return 1;
        });
        when(tableMappingDao.queryByIdIncludingDeleted(501L))
                .thenAnswer(invocation -> storedMapping.get());
        when(dorisClient.tableExists(anyString(), anyString())).thenReturn(false, true);
        when(dorisClient.readContract("ods", "orders"))
                .thenReturn(preview.getTargetContract());
        when(dorisClient.readTableProperties("ods", "orders"))
                .thenReturn(java.util.Map.of("partition.retention_count", "7"));
        when(coordinator.begin(any(LakeOperationIntent.class))).thenAnswer(invocation ->
                new LakeOperationHandle(601L, LakeResourceTypes.ODS_TABLE_MAPPING, 501L,
                        storedMapping.get().getGeneration(), "operation-token", 2));
        doAnswer(invocation -> {
            var operation = invocation.<org.apache.seatunnel.web.api.lake.operation.LakeExternalOperation<?>>
                    getArgument(1);
            Object externalResult = operation.execute();
            return new LakeOperationExecution<>(invocation.getArgument(0), externalResult);
        }).when(coordinator).execute(any(), any());
        when(coordinator.finalizeSuccess(any(), any())).thenAnswer(invocation -> {
            LakeOdsTableMapping mapping = storedMapping.get();
            mapping.setResourceStatus(LakeResourceStatus.READY);
            mapping.setActualTableExists(true);
            mapping.setTargetConsistencyStatus(LakeConsistencyStatus.CONSISTENT);
            mapping.setOperationToken(null);
            return true;
        });

        LakeManagedTableCreateDTO request = new LakeManagedTableCreateDTO();
        request.setPreviewToken(preview.getPreviewToken());
        service.create(request);

        verify(dorisClient).createTable(eq("ods"), eq("orders"),
                eq(preview.getTargetContract()),
                eq(java.util.Map.of("partition.retention_count", "7")));
        verify(dorisClient, never()).createTable(eq("ods"), eq("orders"),
                eq(preview.getTargetContract()));
        verify(dorisClient, never()).alterTableProperties(anyString(), anyString(), any());
    }

    @Test
    void createRereadsSourceAndUsesStructuredContractAfterPreview() {
        LakeManagedTablePreviewVO preview = service.preview(previewRequest());
        AtomicReference<LakeSourceObjectRef> storedSource = new AtomicReference<>();
        AtomicReference<LakeOdsTableMapping> storedMapping = new AtomicReference<>();
        when(sourceObjectRefDao.insert(any(LakeSourceObjectRef.class))).thenAnswer(invocation -> {
            LakeSourceObjectRef reference = invocation.getArgument(0);
            reference.setId(401L);
            storedSource.set(reference);
            return 1;
        });
        when(sourceObjectRefDao.queryByIdIncludingDeleted(401L))
                .thenAnswer(invocation -> storedSource.get());
        when(tableMappingDao.insert(any(LakeOdsTableMapping.class))).thenAnswer(invocation -> {
            LakeOdsTableMapping mapping = invocation.getArgument(0);
            mapping.setId(501L);
            storedMapping.set(mapping);
            return 1;
        });
        when(tableMappingDao.queryByIdIncludingDeleted(501L))
                .thenAnswer(invocation -> storedMapping.get());
        when(dorisClient.tableExists(anyString(), anyString())).thenReturn(false, true);
        when(dorisClient.readContract("ods", "orders"))
                .thenReturn(preview.getTargetContract());
        when(coordinator.begin(any(LakeOperationIntent.class))).thenAnswer(invocation ->
                new LakeOperationHandle(601L, LakeResourceTypes.ODS_TABLE_MAPPING, 501L,
                        storedMapping.get().getGeneration(), "operation-token", 2));
        doAnswer(invocation -> {
            var operation = invocation.<org.apache.seatunnel.web.api.lake.operation.LakeExternalOperation<?>>
                    getArgument(1);
            Object externalResult = operation.execute();
            return new LakeOperationExecution<>(invocation.getArgument(0), externalResult);
        }).when(coordinator).execute(any(), any());
        when(coordinator.finalizeSuccess(any(), any())).thenAnswer(invocation -> {
            LakeOdsTableMapping mapping = storedMapping.get();
            mapping.setResourceStatus(LakeResourceStatus.READY);
            mapping.setActualTableExists(true);
            mapping.setTargetConsistencyStatus(LakeConsistencyStatus.CONSISTENT);
            mapping.setOperationToken(null);
            return true;
        });

        LakeManagedTableCreateDTO request = new LakeManagedTableCreateDTO();
        request.setPreviewToken(preview.getPreviewToken());
        LakeManagedTableVO result = service.create(request);

        assertEquals(LakeResourceStatus.READY, result.getResourceStatus());
        assertTrue(result.getActualTableExists());
        assertEquals(LakeConsistencyStatus.CONSISTENT, result.getTargetConsistencyStatus());
        verify(dorisClient).createTable(eq("ods"), eq("orders"), eq(preview.getTargetContract()));
        verify(coordinator).finalizeSuccess(any(), eq("Doris table exists and matches contract"));
    }

    @Test
    void createRejectsAChangedSourceBeforeAnyDorisMutation() {
        LakeManagedTablePreviewVO preview = service.preview(previewRequest());
        SourceObjectSnapshot changed = source("changed-source-hash");
        when(sourceResolver.resolve(11L, "om-table")).thenReturn(source, changed);

        LakeManagedTableCreateDTO request = new LakeManagedTableCreateDTO();
        request.setPreviewToken(preview.getPreviewToken());
        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.create(request));

        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, exception.getLakeErrorCode());
        verify(dorisClient, never()).createTable(anyString(), anyString(), any(TargetContract.class));
        verify(coordinator, never()).begin(any());
    }

    @Test
    void previewRejectsAnExistingActualTargetWithoutAdoption() {
        when(dorisClient.tableExists("ods", "orders")).thenReturn(true);

        LakeManagedTablePreviewVO result = service.preview(previewRequest());

        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("adoption"));
        verify(tableMappingDao, never()).insert(any());
    }

    @Test
    void deleteImpactBlocksActiveRelationsAndDeleteDoesNotDrop() {
        LakeOdsTableMapping mapping = storedMapping(501L);
        LakeJobRelation relation = new LakeJobRelation();
        relation.setId(801L);
        relation.setTableMappingId(501L);
        relation.setRelationStatus(org.apache.seatunnel.web.common.enums.LakeRelationStatus.ACTIVE);
        relation.setRelationScope(org.apache.seatunnel.web.common.enums.LakeRelationScope.TABLE);
        when(tableMappingDao.queryByIdIncludingDeleted(501L)).thenReturn(mapping);
        when(dorisClient.tableExists("ods", "orders")).thenReturn(true);
        when(jobRelationDao.queryByOdsDatabaseBindingId(21L)).thenReturn(List.of(relation));
        when(lifecycleBindingDao.queryByTableMappingId(501L)).thenReturn(null);

        LakeManagedTableDeleteImpactVO impact = service.deleteImpact(501L);

        assertFalse(impact.isAllowed());
        assertEquals(1, impact.getRelations().size());
        assertFalse(impact.getBlockers().isEmpty());
        LakeManagedTableDeleteDTO request = new LakeManagedTableDeleteDTO();
        request.setTargetTableName("orders");
        request.setImpactHash(impact.getImpactHash());
        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.delete(501L, request));

        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, exception.getLakeErrorCode());
        verify(coordinator, never()).begin(any());
        verify(dorisClient, never()).dropTable(anyString(), anyString());
    }

    @Test
    void deleteImpactBlocksBatchRelationWhenDefinitionIsOnline() {
        LakeOdsTableMapping mapping = storedMapping(505L);
        LakeJobRelation relation = tableRelation(
                805L, 905L, 505L, LakeJobRuntimeType.BATCH);
        when(tableMappingDao.queryByIdIncludingDeleted(505L)).thenReturn(mapping);
        when(jobRelationDao.queryByOdsDatabaseBindingId(21L)).thenReturn(List.of(relation));
        when(batchJobDefinitionDao.queryById(905L)).thenReturn(
                batchDefinition(905L, ReleaseState.ONLINE));
        when(jobInstanceDao.existsRunningInstance(905L)).thenReturn(false);

        LakeManagedTableDeleteImpactVO impact = service.deleteImpact(505L);

        assertFalse(impact.isAllowed());
        assertEquals(List.of(805L), impact.getRelations().stream()
                .map(LakeManagedTableRelationImpactVO::getRelationId).toList());
        assertTrue(impact.getBlockers().stream()
                .anyMatch(blocker -> blocker.contains("online")));
        verify(jobScheduleDao).queryByJobDefinitionId(905L);
        verify(jobInstanceDao).existsRunningInstance(905L);
    }

    @Test
    void deleteImpactBlocksBatchRelationWhenScheduleIsEnabled() {
        LakeOdsTableMapping mapping = storedMapping(506L);
        LakeJobRelation relation = tableRelation(
                806L, 906L, 506L, LakeJobRuntimeType.BATCH);
        when(tableMappingDao.queryByIdIncludingDeleted(506L)).thenReturn(mapping);
        when(jobRelationDao.queryByOdsDatabaseBindingId(21L)).thenReturn(List.of(relation));
        when(batchJobDefinitionDao.queryById(906L)).thenReturn(
                batchDefinition(906L, ReleaseState.OFFLINE));
        when(jobScheduleDao.queryByJobDefinitionId(906L)).thenReturn(schedule(ScheduleStatusEnum.NORMAL));
        when(jobInstanceDao.existsRunningInstance(906L)).thenReturn(false);

        LakeManagedTableDeleteImpactVO impact = service.deleteImpact(506L);

        assertFalse(impact.isAllowed());
        assertTrue(impact.getBlockers().stream()
                .anyMatch(blocker -> blocker.contains("enabled schedule")));
    }

    @Test
    void deleteImpactBlocksBatchRelationWhenInstanceIsRunning() {
        LakeOdsTableMapping mapping = storedMapping(507L);
        mapping.setManagementLevel(LakeManagementLevel.AUTO_CREATED);
        LakeJobRelation relation = tableRelation(
                807L, 907L, 507L, LakeJobRuntimeType.BATCH);
        when(tableMappingDao.queryByIdIncludingDeleted(507L)).thenReturn(mapping);
        when(jobRelationDao.queryByOdsDatabaseBindingId(21L)).thenReturn(List.of(relation));
        when(batchJobDefinitionDao.queryById(907L)).thenReturn(
                batchDefinition(907L, ReleaseState.OFFLINE));
        when(jobInstanceDao.existsRunningInstance(907L)).thenReturn(true);

        LakeManagedTableDeleteImpactVO impact = service.deleteImpact(507L);

        assertFalse(impact.isAllowed());
        assertTrue(impact.getBlockers().stream()
                .anyMatch(blocker -> blocker.contains("running instance")));
    }

    @Test
    void deleteImpactBlocksStreamingRelationWhenDefinitionIsOnline() {
        LakeOdsTableMapping mapping = storedMapping(508L);
        LakeJobRelation relation = tableRelation(
                808L, 908L, 508L, LakeJobRuntimeType.STREAMING);
        when(tableMappingDao.queryByIdIncludingDeleted(508L)).thenReturn(mapping);
        when(jobRelationDao.queryByOdsDatabaseBindingId(21L)).thenReturn(List.of(relation));
        when(streamingJobDefinitionDao.queryById(908L)).thenReturn(
                streamingDefinition(908L, ReleaseState.ONLINE));
        when(streamingJobInstanceDao.existsRunningInstance(908L)).thenReturn(false);

        LakeManagedTableDeleteImpactVO impact = service.deleteImpact(508L);

        assertFalse(impact.isAllowed());
        assertTrue(impact.getBlockers().stream()
                .anyMatch(blocker -> blocker.contains("online")));
    }

    @Test
    void deleteImpactBlocksStreamingRelationWhenInstanceIsRunning() {
        LakeOdsTableMapping mapping = storedMapping(509L);
        LakeJobRelation relation = tableRelation(
                809L, 909L, 509L, LakeJobRuntimeType.STREAMING);
        when(tableMappingDao.queryByIdIncludingDeleted(509L)).thenReturn(mapping);
        when(jobRelationDao.queryByOdsDatabaseBindingId(21L)).thenReturn(List.of(relation));
        when(streamingJobDefinitionDao.queryById(909L)).thenReturn(
                streamingDefinition(909L, ReleaseState.OFFLINE));
        when(streamingJobInstanceDao.existsRunningInstance(909L)).thenReturn(true);

        LakeManagedTableDeleteImpactVO impact = service.deleteImpact(509L);

        assertFalse(impact.isAllowed());
        assertTrue(impact.getBlockers().stream()
                .anyMatch(blocker -> blocker.contains("running instance")));
    }

    @Test
    void deleteImpactAllowsOfflineBatchRelationWithoutScheduleOrRunningInstance() {
        LakeOdsTableMapping mapping = storedMapping(510L);
        LakeJobRelation relation = tableRelation(
                810L, 910L, 510L, LakeJobRuntimeType.BATCH);
        when(tableMappingDao.queryByIdIncludingDeleted(510L)).thenReturn(mapping);
        when(jobRelationDao.queryByOdsDatabaseBindingId(21L)).thenReturn(List.of(relation));
        when(batchJobDefinitionDao.queryById(910L)).thenReturn(
                batchDefinition(910L, ReleaseState.OFFLINE));
        when(jobScheduleDao.queryByJobDefinitionId(910L)).thenReturn(null);
        when(jobInstanceDao.existsRunningInstance(910L)).thenReturn(false);

        LakeManagedTableDeleteImpactVO impact = service.deleteImpact(510L);

        assertTrue(impact.isAllowed());
        assertEquals(1, impact.getRelations().size());
        verify(jobRelationDao, never()).updateById(any());
        verify(tableMappingDao, never()).updateById(any());
    }

    @Test
    void deleteImpactIncludesNamespaceRelationAndRemainsReadOnly() {
        LakeOdsTableMapping mapping = storedMapping(511L);
        LakeJobRelation relation = namespaceRelation(
                811L, 911L, LakeJobRuntimeType.STREAMING);
        when(tableMappingDao.queryByIdIncludingDeleted(511L)).thenReturn(mapping);
        when(jobRelationDao.queryByOdsDatabaseBindingId(21L)).thenReturn(List.of(relation));
        when(streamingJobDefinitionDao.queryById(911L)).thenReturn(
                streamingDefinition(911L, ReleaseState.ONLINE));
        when(streamingJobInstanceDao.existsRunningInstance(911L)).thenReturn(false);

        LakeManagedTableDeleteImpactVO impact = service.deleteImpact(511L);

        assertFalse(impact.isAllowed());
        assertEquals(LakeRelationScope.NAMESPACE,
                impact.getRelations().get(0).getRelationScope());
        assertTrue(impact.getBlockers().stream()
                .anyMatch(blocker -> blocker.contains("online")));
        verify(jobRelationDao, never()).updateById(any());
        verify(jobRelationDao, never()).markStaleByJobId(anyLong());
    }

    @Test
    void deleteUsesImpactConfirmationAndPublishesDropThroughCoordinator() {
        LakeOdsTableMapping mapping = storedMapping(502L);
        when(tableMappingDao.queryByIdIncludingDeleted(502L)).thenReturn(mapping);
        when(dorisClient.tableExists("ods", "orders")).thenReturn(true, true, false);
        when(jobRelationDao.queryByOdsDatabaseBindingId(21L)).thenReturn(List.of());
        when(lifecycleBindingDao.queryByTableMappingId(502L)).thenReturn(null);
        when(coordinator.begin(any(LakeOperationIntent.class))).thenReturn(
                new LakeOperationHandle(602L, LakeResourceTypes.ODS_TABLE_MAPPING,
                        502L, 1, "drop-token", 2));
        doAnswer(invocation -> {
            var operation = invocation.<org.apache.seatunnel.web.api.lake.operation.LakeExternalOperation<?>>
                    getArgument(1);
            Object externalResult = operation.execute();
            return new LakeOperationExecution<>(invocation.getArgument(0), externalResult);
        }).when(coordinator).execute(any(), any());
        when(coordinator.finalizeSuccess(any(), any())).thenAnswer(invocation -> {
            mapping.setResourceStatus(LakeResourceStatus.DELETED);
            mapping.setDeleted(true);
            mapping.setActualTableExists(false);
            mapping.setOperationToken(null);
            return true;
        });
        LakeManagedTableDeleteImpactVO impact = service.deleteImpact(502L);
        LakeManagedTableDeleteDTO request = new LakeManagedTableDeleteDTO();
        request.setTargetTableName("orders");
        request.setImpactHash(impact.getImpactHash());

        service.delete(502L, request);

        verify(dorisClient).dropTable("ods", "orders");
        verify(coordinator).finalizeSuccess(any(), eq("Doris table deleted"));
        assertTrue(mapping.getDeleted());
        assertFalse(mapping.getActualTableExists());
    }

    @Test
    void detailOnlyReadsPersistedContractAndNeverReconcilesDoris() {
        LakeOdsTableMapping mapping = storedMapping(503L);
        LakeSourceObjectRef reference = new LakeSourceObjectRef();
        reference.setId(701L);
        reference.setSourceDataSourceId(11L);
        reference.setOmEntityId("om-table");
        mapping.setSourceObjectRefId(701L);
        when(tableMappingDao.queryByIdIncludingDeleted(503L)).thenReturn(mapping);
        when(sourceObjectRefDao.queryByIdIncludingDeleted(701L)).thenReturn(reference);

        LakeManagedTableVO result = service.detail(503L);

        assertEquals(503L, result.getId());
        assertNotNull(result.getTargetContract());
        verify(dorisClient, never()).tableExists(anyString(), anyString());
        verify(dorisClientProvider, never()).get(anyLong());
        verify(tableMappingDao, never()).updateById(any());
    }

    @Test
    void reconcileReadsThroughEvaluatorBeforeShortPersistenceAndDetailStaysCached() {
        LakeOdsTableMapping mapping = storedMapping(506L);
        when(tableMappingDao.queryByIdIncludingDeleted(506L)).thenReturn(mapping);
        LakeTableDriftEvaluator.Evaluation evaluation = new LakeTableDriftEvaluator.Evaluation(
                506L, LakeManagementLevel.MANAGED,
                new LakeTableDriftEvaluator.DimensionResult(
                        LakeConsistencyStatus.CONSISTENT, "SOURCE_OK", "source"),
                new LakeTableDriftEvaluator.DimensionResult(
                        LakeConsistencyStatus.MISSING,
                        LakeTableDriftEvaluator.TARGET_TABLE_MISSING, "target"),
                new LakeTableDriftEvaluator.DimensionResult(
                        LakeConsistencyStatus.UNBOUND, "TASK_UNBOUND", "task"),
                LakeConsistencyStatus.MISSING, List.of());
        LakeSourceObjectRef reference = new LakeSourceObjectRef();
        reference.setId(701L);
        reference.setSourceDataSourceId(11L);
        reference.setOmEntityId("om-table");
        when(sourceObjectRefDao.queryByIdIncludingDeleted(701L)).thenReturn(reference);
        when(driftEvaluator.evaluate(mapping)).thenReturn(evaluation);
        when(reconcilePersistenceService.persist(mapping, evaluation, 7)).thenReturn(mapping);

        LakeManagedTableVO result = service.reconcile(506L);

        assertEquals(506L, result.getId());
        verify(driftEvaluator).evaluate(mapping);
        verify(reconcilePersistenceService).persist(mapping, evaluation, 7);
        var order = inOrder(driftEvaluator, reconcilePersistenceService);
        order.verify(driftEvaluator).evaluate(mapping);
        order.verify(reconcilePersistenceService).persist(mapping, evaluation, 7);
        verify(tableMappingDao, never()).updateIfTokenAndVersion(any(), any(), any());
        verify(dorisClient, never()).tableExists(anyString(), anyString());
    }

    @Test
    void reconcileRejectsPendingCreateBeforeAnyExternalRead() {
        LakeOdsTableMapping mapping = storedMapping(507L);
        mapping.setResourceStatus(LakeResourceStatus.PENDING_CREATE);
        when(tableMappingDao.queryByIdIncludingDeleted(507L)).thenReturn(mapping);

        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.reconcile(507L));

        assertEquals(LakeErrorCode.LAKE_OPERATION_STALE, exception.getLakeErrorCode());
        verify(driftEvaluator, never()).evaluate(any());
        verify(reconcilePersistenceService, never()).persist(any(), any(), any());
    }

    @Test
    void retryRejectsAnActualTableThatDiffersInsteadOfAdoptingIt() {
        LakeOdsTableMapping mapping = storedMapping(504L);
        when(tableMappingDao.queryByIdIncludingDeleted(504L)).thenReturn(mapping);
        when(dorisClient.tableExists("ods", "orders")).thenReturn(true);
        TargetContract different = new TargetContract(LakeTableModel.DUPLICATE, List.of(
                new TargetColumn("id", 1, "id", TargetType.varchar(255), false, true, 1)),
                List.of("id"), TargetPartition.disabled(), TargetDistribution.random());
        when(dorisClient.readContract("ods", "orders")).thenReturn(different);

        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.retry(504L));

        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, exception.getLakeErrorCode());
        verify(coordinator, never()).begin(any());
        verify(dorisClient, never()).dropTable(anyString(), anyString());
    }

    private LakeManagedTablePreviewDTO previewRequest() {
        LakeManagedTablePreviewDTO request = new LakeManagedTablePreviewDTO();
        request.setSourceDataSourceId(11L);
        request.setOmEntityId("om-table");
        request.setOdsDatabaseBindingId(21L);
        request.setTargetTableName("orders");
        request.setTableModel(LakeTableModel.DUPLICATE);
        return request;
    }

    private LakeManagedTablePreviewDTO previewRequestWithPolicy() {
        LakeManagedTablePreviewDTO request = previewRequest();
        request.setLifecyclePolicyId(901L);
        return request;
    }

    private LakeManagedTablePreviewDTO lifecyclePreviewRequest() {
        LakeManagedTablePreviewDTO request = previewRequestWithPolicy();
        LakeManagedTablePartitionDTO partition = new LakeManagedTablePartitionDTO();
        partition.setEnabled(true);
        partition.setColumn("event_time");
        partition.setGranularity("DAY");
        request.setPartition(partition);
        LakeManagedTableColumnDTO eventTime = new LakeManagedTableColumnDTO();
        eventTime.setSourceField("EVENT_TIME");
        eventTime.setTargetField("event_time");
        eventTime.setTargetType("DATETIME");
        request.setColumns(List.of(eventTime));
        return request;
    }

    private LakeOdsTableMapping storedMapping(Long id) {
        TargetContract contract = new TargetContract(LakeTableModel.DUPLICATE, List.of(
                new TargetColumn("ID", 1, "id", TargetType.varchar(255), false, true, 1),
                new TargetColumn("PAYLOAD", 2, "payload", new TargetType(DorisTypeBase.STRING),
                        true, false, 2)), List.of("id"), TargetPartition.disabled(),
                TargetDistribution.random());
        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.setId(id);
        mapping.setSourceObjectRefId(701L);
        mapping.setOdsDatabaseBindingId(21L);
        mapping.setLakeDataSourceId(31L);
        mapping.setDatabaseName("ods");
        mapping.setTargetTableName("orders");
        mapping.setManagementLevel(LakeManagementLevel.MANAGED);
        mapping.setTableModel(LakeTableModel.DUPLICATE);
        mapping.setSourceSchemaHash(source.sourceSchemaHash());
        mapping.setTargetContractHash(TargetContractCanonicalizer.canonicalHash(contract));
        try {
            mapping.setTargetContractJson(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(contract));
            mapping.setFieldMappingsJson(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(contractFactory.fieldMappings(contract)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
        mapping.setSourceConsistencyStatus(LakeConsistencyStatus.CONSISTENT);
        mapping.setTargetConsistencyStatus(LakeConsistencyStatus.CONSISTENT);
        mapping.setTaskConsistencyStatus(LakeConsistencyStatus.UNBOUND);
        mapping.setActualTableExists(true);
        mapping.setResourceStatus(LakeResourceStatus.READY);
        mapping.setGeneration(1);
        mapping.setLockVersion(1);
        mapping.setDeleted(false);
        return mapping;
    }

    private static LakeJobRelation tableRelation(
            Long relationId, Long jobId, Long mappingId, LakeJobRuntimeType runtimeType) {
        LakeJobRelation relation = new LakeJobRelation();
        relation.setId(relationId);
        relation.setOdsDatabaseBindingId(21L);
        relation.setTableMappingId(mappingId);
        relation.setRelationScope(LakeRelationScope.TABLE);
        relation.setJobRuntimeType(runtimeType);
        relation.setJobId(jobId);
        relation.setJobVersion(1);
        relation.setRelationStatus(org.apache.seatunnel.web.common.enums.LakeRelationStatus.ACTIVE);
        return relation;
    }

    private static LakeJobRelation namespaceRelation(
            Long relationId, Long jobId, LakeJobRuntimeType runtimeType) {
        LakeJobRelation relation = tableRelation(relationId, jobId, null, runtimeType);
        relation.setRelationScope(LakeRelationScope.NAMESPACE);
        return relation;
    }

    private static JobDefinitionEntity batchDefinition(Long id, ReleaseState releaseState) {
        JobDefinitionEntity definition = new JobDefinitionEntity();
        definition.setId(id);
        definition.setReleaseState(releaseState);
        return definition;
    }

    private static StreamingJobDefinitionEntity streamingDefinition(
            Long id, ReleaseState releaseState) {
        StreamingJobDefinitionEntity definition = new StreamingJobDefinitionEntity();
        definition.setId(id);
        definition.setReleaseState(releaseState);
        return definition;
    }

    private static JobSchedule schedule(ScheduleStatusEnum status) {
        JobSchedule schedule = new JobSchedule();
        schedule.setScheduleStatus(status);
        return schedule;
    }

    private static LakeOdsDatabaseBinding binding(Long id) {
        LakeOdsDatabaseBinding binding = new LakeOdsDatabaseBinding();
        binding.setId(id);
        binding.setLakeDataSourceId(31L);
        binding.setSourceDataSourceId(11L);
        binding.setDatabaseName("ods");
        binding.setResourceStatus(LakeResourceStatus.READY);
        binding.setGeneration(1);
        binding.setLockVersion(1);
        binding.setDeleted(false);
        return binding;
    }

    private static SourceObjectSnapshot source(String hash) {
        return new SourceObjectSnapshot(
                "om-table", "svc.db.public.orders",
                List.of(
                        new SourceColumnSnapshot("ID", 1, "BIGINT", "BIGINT", null, 19L, 0L,
                                "PRIMARY_KEY", false),
                        new SourceColumnSnapshot("PAYLOAD", 2, "JSON", "JSON", null, null, null,
                                null, true)),
                List.of(new SourceConstraintSnapshot("PRIMARY_KEY", List.of("ID"), List.of(), null)),
                hash, "snapshot");
    }

    private static SourceObjectSnapshot lifecycleSource() {
        return lifecycleSource(false);
    }

    private static SourceObjectSnapshot lifecycleSource(boolean eventTimeNullable) {
        return new SourceObjectSnapshot(
                "om-table", "svc.db.public.orders",
                List.of(
                        new SourceColumnSnapshot("ID", 1, "BIGINT", "BIGINT", null, 19L, 0L,
                                "PRIMARY_KEY", false),
                        new SourceColumnSnapshot("EVENT_TIME", 2, "DATETIME", "DATETIME", null,
                                null, null, null, eventTimeNullable)),
                List.of(new SourceConstraintSnapshot("PRIMARY_KEY", List.of("ID"), List.of(), null)),
                "source-hash", "snapshot");
    }

    private static LakeLifecyclePolicy lifecyclePolicy() {
        LakeLifecyclePolicy policy = new LakeLifecyclePolicy();
        policy.setId(901L);
        policy.setVersion(3);
        policy.setStatus(LakeLifecyclePolicyStatus.ACTIVE);
        policy.setGranularity(LakePartitionGranularity.DAY);
        policy.setRetentionCount(7);
        return policy;
    }
}
