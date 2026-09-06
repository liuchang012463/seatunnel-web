package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.lake.source.LakeSourceObjectResolver;
import org.apache.seatunnel.web.api.lake.source.SourceColumnSnapshot;
import org.apache.seatunnel.web.api.lake.source.SourceConstraintSnapshot;
import org.apache.seatunnel.web.api.lake.source.SourceObjectSnapshot;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTableVO;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.common.enums.LakeSourceObjectType;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeSourceObjectRef;
import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeSourceObjectRefDao;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeUnmanagedTableBindDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LakeUnmanagedTableBindingServiceImplTest {

    @Mock private LakeOdsDatabaseBindingDao databaseBindingDao;
    @Mock private LakeOdsTableMappingDao tableMappingDao;
    @Mock private LakeSourceObjectRefDao sourceObjectRefDao;
    @Mock private LakeJobRelationDao jobRelationDao;
    @Mock private LakeTableLifecycleBindingDao lifecycleBindingDao;
    @Mock private LakeSourceObjectResolver sourceResolver;
    @Mock private LakeDorisClientProvider dorisClientProvider;
    @Mock private DorisLakeClient dorisClient;
    @Mock private CurrentUserProvider currentUserProvider;
    private LakeUnmanagedTableBindingPersistenceService persistenceService;

    private LakeUnmanagedTableBindingServiceImpl service;
    private LakeOdsDatabaseBinding binding;
    private SourceObjectSnapshot source;

    @BeforeEach
    void setUp() {
        service = new LakeUnmanagedTableBindingServiceImpl(
                databaseBindingDao, tableMappingDao, sourceObjectRefDao, jobRelationDao,
                lifecycleBindingDao, sourceResolver, dorisClientProvider,
                currentUserProvider, persistenceService = spy(
                        new LakeUnmanagedTableBindingPersistenceService(
                                databaseBindingDao, tableMappingDao, sourceObjectRefDao,
                                jobRelationDao, lifecycleBindingDao)));
        binding = binding();
        source = source();
        lenient().when(currentUserProvider.getCurrentUserId()).thenReturn(7);
        lenient().when(databaseBindingDao.queryActiveById(21L)).thenReturn(binding);
        lenient().when(sourceResolver.resolve(11L, "om-table")).thenReturn(source);
        lenient().when(dorisClientProvider.get(31L)).thenReturn(dorisClient);
        lenient().when(dorisClient.tableExists("ods", "orders")).thenReturn(true);
        lenient().when(sourceObjectRefDao.queryBySourceDataSourceIdAndOmEntityId(11L, "om-table"))
                .thenReturn(null);
        lenient().when(sourceObjectRefDao.queryByOmEntityIdIncludingDeleted("om-table"))
                .thenReturn(null);
        lenient().when(tableMappingDao.queryByBindingIdAndTargetTable(21L, "orders"))
                .thenReturn(null);
        lenient().when(tableMappingDao.queryByBindingIdAndTargetTableIncludingDeleted(21L, "orders"))
                .thenReturn(null);
        lenient().when(tableMappingDao.queryByBindingIdAndSourceObject(anyLong(), anyLong()))
                .thenReturn(null);
        lenient().when(tableMappingDao.queryByBindingIdAndSourceObjectIncludingDeleted(anyLong(), anyLong()))
                .thenReturn(null);
    }

    @Test
    void bindReadsRemoteStateBeforeShortLocalTransactionAndCreatesReadyUnmanagedMapping() {
        when(sourceObjectRefDao.insert(any(LakeSourceObjectRef.class))).thenAnswer(invocation -> {
            LakeSourceObjectRef reference = invocation.getArgument(0);
            reference.setId(101L);
            return 1;
        });
        when(tableMappingDao.insert(any(LakeOdsTableMapping.class))).thenAnswer(invocation -> {
            LakeOdsTableMapping mapping = invocation.getArgument(0);
            mapping.setId(201L);
            return 1;
        });

        LakeManagedTableVO result = service.bind(request());

        assertEquals(201L, result.getId());
        assertEquals(LakeManagementLevel.UNMANAGED, result.getManagementLevel());
        assertEquals(LakeResourceStatus.READY, result.getResourceStatus());
        assertTrue(result.getActualTableExists());
        assertEquals(LakeConsistencyStatus.CONSISTENT, result.getSourceConsistencyStatus());
        assertEquals(LakeConsistencyStatus.UNKNOWN, result.getTargetConsistencyStatus());
        assertEquals(LakeConsistencyStatus.UNBOUND, result.getTaskConsistencyStatus());
        verify(tableMappingDao).insert(any(LakeOdsTableMapping.class));
        verify(dorisClient, never()).createTable(anyString(), anyString(), any());
        verify(dorisClient, never()).dropTable(anyString(), anyString());
        verify(dorisClient, never()).readContract(anyString(), anyString());
        InOrder order = inOrder(sourceResolver, dorisClient, persistenceService);
        order.verify(sourceResolver).resolve(11L, "om-table");
        order.verify(dorisClient).tableExists("ods", "orders");
        order.verify(persistenceService).persistBind(
                any(LakeOdsDatabaseBinding.class), anyString(), any(SourceObjectSnapshot.class), any());
    }

    @Test
    void bindIsIdempotentForTheSameActiveIdentityWithoutWriting() {
        LakeSourceObjectRef reference = sourceRef(101L);
        LakeOdsTableMapping mapping = mapping(201L, reference.getId());
        when(sourceObjectRefDao.queryBySourceDataSourceIdAndOmEntityId(11L, "om-table"))
                .thenReturn(reference);
        when(tableMappingDao.queryByBindingIdAndTargetTable(21L, "orders"))
                .thenReturn(mapping);

        LakeManagedTableVO result = service.bind(request());

        assertEquals(201L, result.getId());
        verify(sourceObjectRefDao, never()).insert(any());
        verify(tableMappingDao, never()).insert(any());
        verify(persistenceService).persistBind(
                any(LakeOdsDatabaseBinding.class), anyString(), any(SourceObjectSnapshot.class), any());
    }

    @Test
    void bindRejectsOpenMetadataMissingAndUnknownBeforeDorisOrWrites() {
        doThrow(new LakeServiceException(LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING,
                "internal details"), new LakeServiceException(
                LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN, "secret"))
                .when(sourceResolver).resolve(11L, "om-table");

        LakeServiceException exception = assertThrows(LakeServiceException.class,
                () -> service.bind(request()));

        assertEquals(LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING, exception.getLakeErrorCode());
        verify(dorisClientProvider, never()).get(anyLong());
        verify(sourceObjectRefDao, never()).insert(any());
        verify(tableMappingDao, never()).insert(any());

        LakeServiceException unknown = assertThrows(LakeServiceException.class,
                () -> service.bind(request()));
        assertEquals(LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN, unknown.getLakeErrorCode());
        assertFalse(unknown.getMessage().contains("secret"));
    }

    @Test
    void bindRejectsMissingOrUnavailableDorisWithoutWrites() {
        when(dorisClient.tableExists("ods", "orders")).thenReturn(false);
        LakeServiceException missing = assertThrows(LakeServiceException.class,
                () -> service.bind(request()));
        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, missing.getLakeErrorCode());
        verify(tableMappingDao, never()).insert(any());

        when(dorisClient.tableExists("ods", "orders"))
                .thenThrow(new LakeServiceException(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                        "password"));
        LakeServiceException unavailable = assertThrows(LakeServiceException.class,
                () -> service.bind(request()));
        assertEquals(LakeErrorCode.LAKE_DORIS_UNAVAILABLE, unavailable.getLakeErrorCode());
        assertFalse(unavailable.getMessage().contains("password"));
    }

    @Test
    void bindRejectsSourceOrBindingOwnershipMismatch() {
        LakeUnmanagedTableBindDTO wrongSource = request();
        wrongSource.setSourceDataSourceId(12L);
        LakeServiceException sourceException = assertThrows(LakeServiceException.class,
                () -> service.bind(wrongSource));
        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, sourceException.getLakeErrorCode());
        verify(sourceResolver, never()).resolve(anyLong(), anyString());

        binding.setResourceStatus(LakeResourceStatus.CREATING);
        LakeServiceException stateException = assertThrows(LakeServiceException.class,
                () -> service.bind(request()));
        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, stateException.getLakeErrorCode());
    }

    @Test
    void bindRejectsSourceAndMappingTombstones() {
        LakeSourceObjectRef tombstone = sourceRef(101L);
        tombstone.setDeleted(true);
        tombstone.setResourceStatus(LakeResourceStatus.DELETED);
        when(sourceObjectRefDao.queryByOmEntityIdIncludingDeleted("om-table"))
                .thenReturn(tombstone);
        LakeServiceException sourceException = assertThrows(LakeServiceException.class,
                () -> service.bind(request()));
        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, sourceException.getLakeErrorCode());

        when(sourceObjectRefDao.queryByOmEntityIdIncludingDeleted("om-table"))
                .thenReturn(null);
        LakeOdsTableMapping mappingTombstone = mapping(201L, 101L);
        mappingTombstone.setDeleted(true);
        when(tableMappingDao.queryByBindingIdAndTargetTableIncludingDeleted(21L, "orders"))
                .thenReturn(mappingTombstone);
        LakeServiceException mappingException = assertThrows(LakeServiceException.class,
                () -> service.bind(request()));
        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, mappingException.getLakeErrorCode());
        verify(tableMappingDao, never()).insert(any());
    }

    @Test
    void bindRechecksBindingInsideLocalTransactionBeforeWriting() {
        LakeOdsDatabaseBinding changed = binding();
        changed.setResourceStatus(LakeResourceStatus.DELETED);
        when(databaseBindingDao.queryActiveById(21L)).thenReturn(binding, changed);
        LakeServiceException exception = assertThrows(LakeServiceException.class,
                () -> service.bind(request()));

        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, exception.getLakeErrorCode());
        verify(sourceObjectRefDao, never()).insert(any());
        verify(tableMappingDao, never()).insert(any());
    }

    @Test
    void unbindSoftDeletesOnlyUnmanagedMappingAndPreservesGeneration() {
        LakeOdsTableMapping mapping = mapping(201L, 101L);
        mapping.setGeneration(8);
        when(tableMappingDao.queryByIdIncludingDeleted(201L)).thenReturn(mapping);
        when(jobRelationDao.queryByOdsDatabaseBindingId(21L)).thenReturn(List.of());
        when(lifecycleBindingDao.queryByTableMappingId(201L)).thenReturn(null);
        when(tableMappingDao.updateIfTokenAndVersionIncludingDeleted(mapping, null, 4))
                .thenReturn(true);
        LakeSourceObjectRef reference = sourceRef(101L);
        when(sourceObjectRefDao.queryByIdIncludingDeleted(101L)).thenReturn(reference);

        LakeManagedTableVO result = service.unbind(201L);

        assertTrue(result.getDeleted());
        assertEquals(LakeResourceStatus.DELETED, result.getResourceStatus());
        assertEquals(8, result.getGeneration());
        verify(tableMappingDao).updateIfTokenAndVersionIncludingDeleted(mapping, null, 4);
        verify(sourceObjectRefDao, never()).deleteById(anyLong());
        verify(dorisClient, never()).dropTable(anyString(), anyString());
    }

    @Test
    void unbindIsIdempotentForDeletedMappingAndGuardsRelationsLifecycleAndManagement() {
        LakeOdsTableMapping deleted = mapping(201L, 101L);
        deleted.setDeleted(true);
        deleted.setResourceStatus(LakeResourceStatus.DELETED);
        when(tableMappingDao.queryByIdIncludingDeleted(201L)).thenReturn(deleted);
        LakeManagedTableVO repeated = service.unbind(201L);
        assertTrue(repeated.getDeleted());
        verify(tableMappingDao, never()).updateIfTokenAndVersionIncludingDeleted(any(), any(), any());

        LakeOdsTableMapping managed = mapping(202L, 101L);
        managed.setManagementLevel(LakeManagementLevel.MANAGED);
        when(tableMappingDao.queryByIdIncludingDeleted(202L)).thenReturn(managed);
        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT,
                assertThrows(LakeServiceException.class, () -> service.unbind(202L))
                        .getLakeErrorCode());

        LakeOdsTableMapping referenced = mapping(203L, 101L);
        LakeJobRelation relation = new LakeJobRelation();
        relation.setTableMappingId(203L);
        relation.setRelationScope(LakeRelationScope.TABLE);
        relation.setRelationStatus(LakeRelationStatus.ACTIVE);
        when(tableMappingDao.queryByIdIncludingDeleted(203L)).thenReturn(referenced);
        when(jobRelationDao.queryByOdsDatabaseBindingId(21L)).thenReturn(List.of(relation));
        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT,
                assertThrows(LakeServiceException.class, () -> service.unbind(203L))
                        .getLakeErrorCode());

        LakeOdsTableMapping lifecycle = mapping(204L, 101L);
        LakeTableLifecycleBinding lifecycleBinding = new LakeTableLifecycleBinding();
        lifecycleBinding.setStatus(LakeLifecycleBindingStatus.ACTIVE);
        when(tableMappingDao.queryByIdIncludingDeleted(204L)).thenReturn(lifecycle);
        when(jobRelationDao.queryByOdsDatabaseBindingId(21L)).thenReturn(List.of());
        when(lifecycleBindingDao.queryByTableMappingId(204L)).thenReturn(lifecycleBinding);
        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT,
                assertThrows(LakeServiceException.class, () -> service.unbind(204L))
                        .getLakeErrorCode());
    }

    @Test
    void bindingOrchestrationIsNotTransactionalButPersistenceBoundaryIs() throws Exception {
        assertEquals(null, LakeUnmanagedTableBindingServiceImpl.class
                .getMethod("bind", LakeUnmanagedTableBindDTO.class)
                .getAnnotation(Transactional.class));
        assertNotNull(LakeUnmanagedTableBindingPersistenceService.class
                .getMethod("persistBind", LakeOdsDatabaseBinding.class, String.class,
                        SourceObjectSnapshot.class, Integer.class)
                .getAnnotation(Transactional.class));
    }

    private LakeUnmanagedTableBindDTO request() {
        LakeUnmanagedTableBindDTO request = new LakeUnmanagedTableBindDTO();
        request.setOdsDatabaseBindingId(21L);
        request.setTargetTableName("orders");
        request.setSourceDataSourceId(11L);
        request.setOmEntityId("om-table");
        return request;
    }

    private LakeOdsDatabaseBinding binding() {
        LakeOdsDatabaseBinding result = new LakeOdsDatabaseBinding();
        result.setId(21L);
        result.setSourceDataSourceId(11L);
        result.setLakeDataSourceId(31L);
        result.setDatabaseName("ods");
        result.setResourceStatus(LakeResourceStatus.READY);
        result.setDeleted(false);
        return result;
    }

    private SourceObjectSnapshot source() {
        return new SourceObjectSnapshot(
                "om-table", "svc.db.public.orders",
                List.of(new SourceColumnSnapshot("id", 1, "BIGINT", "BIGINT",
                        null, 19L, 0L, "PRIMARY_KEY", false)),
                List.of(new SourceConstraintSnapshot("PRIMARY_KEY", List.of("id"), List.of(), null)),
                "source-hash", "snapshot");
    }

    private LakeSourceObjectRef sourceRef(Long id) {
        LakeSourceObjectRef reference = new LakeSourceObjectRef();
        reference.setId(id);
        reference.setSourceDataSourceId(11L);
        reference.setOmEntityId("om-table");
        reference.setObjectType(LakeSourceObjectType.TABLE);
        reference.setResourceStatus(LakeResourceStatus.READY);
        reference.setLockVersion(1);
        reference.setDeleted(false);
        return reference;
    }

    private LakeOdsTableMapping mapping(Long id, Long sourceRefId) {
        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.setId(id);
        mapping.setSourceObjectRefId(sourceRefId);
        mapping.setOdsDatabaseBindingId(21L);
        mapping.setLakeDataSourceId(31L);
        mapping.setDatabaseName("ods");
        mapping.setTargetTableName("orders");
        mapping.setManagementLevel(LakeManagementLevel.UNMANAGED);
        mapping.setResourceStatus(LakeResourceStatus.READY);
        mapping.setLockVersion(4);
        mapping.setGeneration(1);
        mapping.setDeleted(false);
        mapping.setActualTableExists(true);
        return mapping;
    }
}
