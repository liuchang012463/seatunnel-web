package org.apache.seatunnel.web.api.service.application;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.job.LakeExactSingleProjectionPlanner;
import org.apache.seatunnel.web.api.lake.source.LakeSourceObjectResolver;
import org.apache.seatunnel.web.api.lake.source.SourceObjectSnapshot;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeSourceObjectRef;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeSourceObjectRefDao;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LakeExactSingleProjectionApplicationServiceTest {

    private static final long BINDING_ID = 7L;
    private static final long SOURCE_DATA_SOURCE_ID = 10L;
    private static final long LAKE_DATA_SOURCE_ID = 99L;
    private static final String OM_ENTITY_ID = "om-table-1";

    @Mock private LakeExactSingleProjectionPlanner planner;
    @Mock private LakeSourceObjectResolver sourceResolver;
    @Mock private LakeSourceObjectRefDao sourceObjectRefDao;
    @Mock private LakeOdsTableMappingDao tableMappingDao;
    @Mock private JobDefinitionSaveCommand command;

    private LakeExactSingleProjectionApplicationService service;
    private SourceObjectSnapshot sourceSnapshot;

    @BeforeEach
    void setUp() {
        service = new LakeExactSingleProjectionApplicationService(
                planner, sourceResolver, sourceObjectRefDao, tableMappingDao);
        sourceSnapshot = new SourceObjectSnapshot(
                OM_ENTITY_ID,
                "mysql.orders.public.orders",
                List.of(),
                List.of(),
                "source-hash",
                "{\"source\":\"orders\"}");
    }

    @Test
    void notApplicableReturnsEmptyWithoutSourceRead() {
        when(planner.plan(command)).thenReturn(
                LakeExactSingleProjectionPlanner.ProjectionPlan.notApplicable());

        assertNull(service.prepare(command));

        verifyNoInteractions(sourceResolver, sourceObjectRefDao, tableMappingDao);
    }

    @Test
    void createAutoPrepareReadsFreshSourceSnapshot() {
        LakeExactSingleProjectionPlanner.ProjectionPlan plan = plan(
                LakeExactSingleProjectionPlanner.Decision.CREATE_AUTO_PENDING);
        when(planner.plan(command)).thenReturn(plan);
        when(sourceResolver.resolve(SOURCE_DATA_SOURCE_ID, OM_ENTITY_ID))
                .thenReturn(sourceSnapshot);

        LakeExactSingleProjectionApplicationService.PreparedProjection prepared =
                service.prepare(command);

        assertEquals(plan, prepared.plan());
        assertEquals(sourceSnapshot, prepared.sourceSnapshot());
        verify(sourceResolver).resolve(SOURCE_DATA_SOURCE_ID, OM_ENTITY_ID);
    }

    @Test
    void unmanagedPrepareReadsFreshSourceSnapshot() {
        LakeExactSingleProjectionPlanner.ProjectionPlan plan = plan(
                LakeExactSingleProjectionPlanner.Decision.CREATE_UNMANAGED_READY);
        when(planner.plan(command)).thenReturn(plan);
        when(sourceResolver.resolve(SOURCE_DATA_SOURCE_ID, OM_ENTITY_ID))
                .thenReturn(sourceSnapshot);

        LakeExactSingleProjectionApplicationService.PreparedProjection prepared =
                service.prepare(command);

        assertEquals(
                LakeExactSingleProjectionPlanner.Decision.CREATE_UNMANAGED_READY,
                prepared.plan().decision());
        verify(sourceResolver).resolve(SOURCE_DATA_SOURCE_ID, OM_ENTITY_ID);
    }

    @Test
    void createPrepareRequiresOpenMetadataUuid() {
        LakeExactSingleProjectionPlanner.ProjectionPlan plan = newPlan(
                LakeExactSingleProjectionPlanner.Decision.CREATE_AUTO_PENDING,
                new LakeExactSingleProjectionPlanner.Endpoint(
                        SOURCE_DATA_SOURCE_ID, "orders", null));
        when(planner.plan(command)).thenReturn(plan);

        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.prepare(command));

        assertEquals(LakeErrorCode.LAKE_REQUEST_INVALID, exception.getLakeErrorCode());
        verifyNoInteractions(sourceResolver);
    }

    @Test
    void sourceMissingAndUnknownKeepStableClassification() {
        LakeExactSingleProjectionPlanner.ProjectionPlan plan = plan(
                LakeExactSingleProjectionPlanner.Decision.CREATE_AUTO_PENDING);
        when(planner.plan(command)).thenReturn(plan);
        when(sourceResolver.resolve(SOURCE_DATA_SOURCE_ID, OM_ENTITY_ID))
                .thenThrow(new LakeServiceException(
                        LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING, "remote details"));

        LakeServiceException missing = assertThrows(
                LakeServiceException.class, () -> service.prepare(command));
        assertEquals(LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING, missing.getLakeErrorCode());
        assertEquals(null, missing.getCause());

        doThrow(new LakeServiceException(
                LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN, "timeout secret"))
                .when(sourceResolver).resolve(SOURCE_DATA_SOURCE_ID, OM_ENTITY_ID);
        LakeServiceException unknown = assertThrows(
                LakeServiceException.class, () -> service.prepare(command));
        assertEquals(LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN, unknown.getLakeErrorCode());
        assertEquals(null, unknown.getCause());
    }

    @Test
    void unknownAndRejectPlansBecomeStableExceptions() {
        for (LakeExactSingleProjectionPlanner.Decision decision : List.of(
                LakeExactSingleProjectionPlanner.Decision.UNKNOWN,
                LakeExactSingleProjectionPlanner.Decision.REJECT)) {
            when(planner.plan(command)).thenReturn(plan(decision));

            LakeServiceException exception = assertThrows(
                    LakeServiceException.class, () -> service.prepare(command));

            assertEquals(LakeErrorCode.LAKE_REQUEST_INVALID, exception.getLakeErrorCode());
            assertEquals(null, exception.getCause());
        }
    }

    @Test
    void applyCreatesAutoPendingProjectionWithoutContractOrDdl() {
        LakeExactSingleProjectionPlanner.ProjectionPlan plan = plan(
                LakeExactSingleProjectionPlanner.Decision.CREATE_AUTO_PENDING);
        LakeExactSingleProjectionApplicationService.PreparedProjection prepared = prepared(plan);
        when(sourceObjectRefDao.queryByOmEntityId(OM_ENTITY_ID)).thenReturn(null);
        when(sourceObjectRefDao.queryByOmEntityIdIncludingDeleted(OM_ENTITY_ID)).thenReturn(null);
        when(tableMappingDao.queryByBindingIdAndTargetTable(BINDING_ID, "ods_orders"))
                .thenReturn(null);
        when(tableMappingDao.queryByBindingIdAndTargetTableIncludingDeleted(BINDING_ID, "ods_orders"))
                .thenReturn(null);
        when(sourceObjectRefDao.insert(any(LakeSourceObjectRef.class))).thenAnswer(invocation -> {
            LakeSourceObjectRef ref = invocation.getArgument(0);
            ref.setId(101L);
            return 1;
        });
        when(tableMappingDao.insert(any(LakeOdsTableMapping.class))).thenAnswer(invocation -> {
            LakeOdsTableMapping mapping = invocation.getArgument(0);
            mapping.setId(201L);
            return 1;
        });

        LakeOdsTableMapping mapping = service.applyPrepared(prepared, 42);

        assertEquals(201L, mapping.getId());
        assertEquals(101L, mapping.getSourceObjectRefId());
        assertEquals(LakeManagementLevel.AUTO_CREATED, mapping.getManagementLevel());
        assertEquals(LakeResourceStatus.PENDING_CREATE, mapping.getResourceStatus());
        assertEquals(Boolean.FALSE, mapping.getActualTableExists());
        assertEquals(LakeConsistencyStatus.CONSISTENT, mapping.getSourceConsistencyStatus());
        assertEquals(LakeConsistencyStatus.UNKNOWN, mapping.getTargetConsistencyStatus());
        assertEquals(LakeConsistencyStatus.UNBOUND, mapping.getTaskConsistencyStatus());
        assertNull(mapping.getTargetContractJson());
        assertNull(mapping.getTargetContractHash());
        assertEquals(1, mapping.getGeneration());
        assertEquals(1, mapping.getLockVersion());
        assertEquals(42, mapping.getCreateUserId());
        verify(tableMappingDao).insert(any(LakeOdsTableMapping.class));
    }

    @Test
    void applyCreatesUnmanagedReadyProjection() {
        LakeExactSingleProjectionPlanner.ProjectionPlan plan = plan(
                LakeExactSingleProjectionPlanner.Decision.CREATE_UNMANAGED_READY);
        LakeExactSingleProjectionApplicationService.PreparedProjection prepared = prepared(plan);
        stubEmptyProjectionState();
        when(sourceObjectRefDao.insert(any(LakeSourceObjectRef.class))).thenAnswer(invocation -> {
            LakeSourceObjectRef ref = invocation.getArgument(0);
            ref.setId(102L);
            return 1;
        });
        when(tableMappingDao.insert(any(LakeOdsTableMapping.class))).thenAnswer(invocation -> {
            LakeOdsTableMapping mapping = invocation.getArgument(0);
            mapping.setId(202L);
            return 1;
        });

        LakeOdsTableMapping mapping = service.applyPrepared(prepared, 43);

        assertEquals(LakeManagementLevel.UNMANAGED, mapping.getManagementLevel());
        assertEquals(LakeResourceStatus.READY, mapping.getResourceStatus());
        assertEquals(Boolean.TRUE, mapping.getActualTableExists());
        assertEquals(LakeConsistencyStatus.CONSISTENT, mapping.getTargetConsistencyStatus());
        assertEquals(43, mapping.getUpdateUserId());
    }

    @Test
    void existingMappingPlanDoesNotWriteProjectionRows() {
        LakeExactSingleProjectionPlanner.ProjectionPlan plan = plan(
                LakeExactSingleProjectionPlanner.Decision.REUSE_AUTO_CREATED);
        when(planner.plan(command)).thenReturn(plan);

        LakeExactSingleProjectionApplicationService.PreparedProjection prepared =
                service.prepare(command);

        assertNull(prepared.sourceSnapshot());
        assertNull(service.applyPrepared(prepared, 44));
        verifyNoInteractions(sourceResolver, sourceObjectRefDao, tableMappingDao);
    }

    @Test
    void sourceTombstoneCannotBeReactivated() {
        LakeExactSingleProjectionPlanner.ProjectionPlan plan = plan(
                LakeExactSingleProjectionPlanner.Decision.CREATE_AUTO_PENDING);
        LakeSourceObjectRef tombstone = sourceRef(301L);
        tombstone.setDeleted(true);
        when(sourceObjectRefDao.queryByOmEntityId(OM_ENTITY_ID)).thenReturn(null);
        when(sourceObjectRefDao.queryByOmEntityIdIncludingDeleted(OM_ENTITY_ID))
                .thenReturn(tombstone);

        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.applyPrepared(prepared(plan), 45));

        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, exception.getLakeErrorCode());
        verify(sourceObjectRefDao, never()).insert(any(LakeSourceObjectRef.class));
        verifyNoInteractions(tableMappingDao);
    }

    @Test
    void mappingTombstoneCannotBeReactivated() {
        LakeExactSingleProjectionPlanner.ProjectionPlan plan = plan(
                LakeExactSingleProjectionPlanner.Decision.CREATE_AUTO_PENDING);
        stubEmptySourceState();
        when(tableMappingDao.queryByBindingIdAndTargetTable(BINDING_ID, "ods_orders"))
                .thenReturn(null);
        LakeOdsTableMapping tombstone = mapping(302L, 401L);
        tombstone.setDeleted(true);
        when(tableMappingDao.queryByBindingIdAndTargetTableIncludingDeleted(BINDING_ID, "ods_orders"))
                .thenReturn(tombstone);

        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.applyPrepared(prepared(plan), 46));

        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, exception.getLakeErrorCode());
        verify(sourceObjectRefDao, never()).insert(any(LakeSourceObjectRef.class));
        verify(sourceObjectRefDao, never()).updateById(any(LakeSourceObjectRef.class));
        verify(tableMappingDao, never()).insert(any(LakeOdsTableMapping.class));
    }

    @Test
    void duplicateInsertsRecheckAndReuseMatchingRows() {
        LakeExactSingleProjectionPlanner.ProjectionPlan plan = plan(
                LakeExactSingleProjectionPlanner.Decision.CREATE_AUTO_PENDING);
        stubEmptyProjectionState();
        LakeSourceObjectRef concurrentRef = sourceRef(401L);
        when(sourceObjectRefDao.insert(any(LakeSourceObjectRef.class)))
                .thenThrow(new DuplicateKeyException("om unique"));
        when(sourceObjectRefDao.queryByOmEntityId(OM_ENTITY_ID))
                .thenReturn(null, concurrentRef);

        LakeOdsTableMapping concurrentMapping = mapping(501L, 401L);
        when(tableMappingDao.insert(any(LakeOdsTableMapping.class)))
                .thenThrow(new DuplicateKeyException("target unique"));
        when(tableMappingDao.queryByBindingIdAndTargetTable(BINDING_ID, "ods_orders"))
                .thenReturn(null, concurrentMapping);

        LakeOdsTableMapping result = service.applyPrepared(prepared(plan), 47);

        assertEquals(501L, result.getId());
        verify(sourceObjectRefDao).queryByOmEntityIdIncludingDeleted(OM_ENTITY_ID);
        verify(tableMappingDao).queryByBindingIdAndTargetTableIncludingDeleted(BINDING_ID, "ods_orders");
    }

    @Test
    void mismatchedActiveMappingIsAStableConflictBeforeWrites() {
        LakeExactSingleProjectionPlanner.ProjectionPlan plan = plan(
                LakeExactSingleProjectionPlanner.Decision.CREATE_AUTO_PENDING);
        LakeSourceObjectRef ref = sourceRef(601L);
        when(sourceObjectRefDao.queryByOmEntityId(OM_ENTITY_ID)).thenReturn(ref);
        LakeOdsTableMapping mismatched = mapping(602L, 999L);
        when(tableMappingDao.queryByBindingIdAndTargetTable(BINDING_ID, "ods_orders"))
                .thenReturn(mismatched);

        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.applyPrepared(prepared(plan), 48));

        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, exception.getLakeErrorCode());
        verify(sourceObjectRefDao, never()).updateById(any(LakeSourceObjectRef.class));
        verify(tableMappingDao, never()).insert(any(LakeOdsTableMapping.class));
    }

    private LakeExactSingleProjectionApplicationService.PreparedProjection prepared(
            LakeExactSingleProjectionPlanner.ProjectionPlan plan) {
        return new LakeExactSingleProjectionApplicationService.PreparedProjection(
                plan, sourceSnapshot);
    }

    private LakeExactSingleProjectionPlanner.ProjectionPlan plan(
            LakeExactSingleProjectionPlanner.Decision decision) {
        return newPlan(decision, new LakeExactSingleProjectionPlanner.Endpoint(
                SOURCE_DATA_SOURCE_ID, "orders", OM_ENTITY_ID));
    }

    private LakeExactSingleProjectionPlanner.ProjectionPlan newPlan(
            LakeExactSingleProjectionPlanner.Decision decision,
            LakeExactSingleProjectionPlanner.Endpoint source) {
        return new LakeExactSingleProjectionPlanner.ProjectionPlan(
                decision,
                JobDefinitionMode.GUIDE_SINGLE,
                LakeJobRuntimeType.BATCH,
                new LakeExactSingleProjectionPlanner.BindingSnapshot(
                        BINDING_ID,
                        LAKE_DATA_SOURCE_ID,
                        SOURCE_DATA_SOURCE_ID,
                        "ods_demo",
                        LakeResourceStatus.READY,
                        false),
                source,
                new LakeExactSingleProjectionPlanner.Endpoint(
                        LAKE_DATA_SOURCE_ID, "ods_orders", null),
                "ods_demo",
                "ods_orders",
                "CREATE_SCHEMA_WHEN_NOT_EXIST",
                null,
                decision == LakeExactSingleProjectionPlanner.Decision.CREATE_UNMANAGED_READY,
                null,
                null);
    }

    private void stubEmptyProjectionState() {
        stubEmptySourceState();
        when(tableMappingDao.queryByBindingIdAndTargetTable(BINDING_ID, "ods_orders"))
                .thenReturn(null);
        when(tableMappingDao.queryByBindingIdAndTargetTableIncludingDeleted(BINDING_ID, "ods_orders"))
                .thenReturn(null);
    }

    private void stubEmptySourceState() {
        when(sourceObjectRefDao.queryByOmEntityId(OM_ENTITY_ID)).thenReturn(null);
        when(sourceObjectRefDao.queryByOmEntityIdIncludingDeleted(OM_ENTITY_ID)).thenReturn(null);
    }

    private LakeSourceObjectRef sourceRef(long id) {
        LakeSourceObjectRef ref = new LakeSourceObjectRef();
        ref.setId(id);
        ref.setSourceDataSourceId(SOURCE_DATA_SOURCE_ID);
        ref.setOmEntityId(OM_ENTITY_ID);
        ref.setResourceStatus(LakeResourceStatus.READY);
        ref.setDeleted(false);
        return ref;
    }

    private LakeOdsTableMapping mapping(long id, long sourceRefId) {
        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.setId(id);
        mapping.setSourceObjectRefId(sourceRefId);
        mapping.setOdsDatabaseBindingId(BINDING_ID);
        mapping.setLakeDataSourceId(LAKE_DATA_SOURCE_ID);
        mapping.setDatabaseName("ods_demo");
        mapping.setTargetTableName("ods_orders");
        mapping.setDeleted(false);
        return mapping;
    }
}
