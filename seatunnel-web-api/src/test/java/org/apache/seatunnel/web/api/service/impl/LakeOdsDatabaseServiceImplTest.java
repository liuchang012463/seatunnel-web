package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.lake.ods.LakeOdsMasterDataResolver;
import org.apache.seatunnel.web.api.lake.ods.OdsDatabaseName;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationExecution;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationHandle;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationIntent;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceOperationCoordinator;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeOdsDatabaseCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakePhysicalDataSourcePageDTO;
import org.apache.seatunnel.web.spi.enums.DbType;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LakeOdsDatabaseServiceImplTest {

    @Mock private DataSourceDao dataSourceDao;
    @Mock private BusinessSystemDao businessSystemDao;
    @Mock private DataSourceUnitDao dataSourceUnitDao;
    @Mock private LakeOdsDatabaseBindingDao bindingDao;
    @Mock private LakeOdsTableMappingDao tableMappingDao;
    @Mock private LakeJobRelationDao jobRelationDao;
    @Mock private LakeOdsMasterDataResolver masterDataResolver;
    @Mock private LakeDorisClientProvider dorisClientProvider;
    @Mock private LakeResourceOperationCoordinator coordinator;
    @Mock private org.apache.seatunnel.web.api.security.CurrentUserProvider currentUserProvider;
    @Mock private DorisLakeClient dorisClient;

    private LakeOdsDatabaseServiceImpl service;
    private LakeProperties lakeProperties;

    @BeforeEach
    void setUp() {
        lakeProperties = new LakeProperties();
        lakeProperties.setEnabled(true);
        lakeProperties.setDataSourceId(99L);
        service = new LakeOdsDatabaseServiceImpl(
                dataSourceDao, businessSystemDao, dataSourceUnitDao, bindingDao,
                tableMappingDao, jobRelationDao, masterDataResolver, dorisClientProvider,
                coordinator, currentUserProvider, lakeProperties);

        lenient().when(currentUserProvider.getCurrentUserId()).thenReturn(12);
        lenient().when(dataSourceDao.queryById(7L)).thenReturn(source(7L, "orders", 11L, DbType.MYSQL));
        lenient().when(dataSourceDao.queryById(99L)).thenReturn(source(99L, "lake", null, DbType.DORIS));
        lenient().when(masterDataResolver.resolve(7L, "orders")).thenReturn(
                new OdsDatabaseName("unit", "system", "orders", "ods_unit_system_orders"));
        lenient().when(dorisClientProvider.get(99L)).thenReturn(dorisClient);
    }

    @Test
    void createPersistsCanonicalNameBeforeIntentAndPublishesAfterVerifiedDorisState() {
        AtomicReference<LakeOdsDatabaseBinding> stored = new AtomicReference<>();
        when(bindingDao.queryBySourceDataSourceId(7L)).thenReturn(null);
        when(bindingDao.queryBySourceDataSourceIdIncludingDeleted(7L)).thenReturn(null);
        when(bindingDao.insert(any(LakeOdsDatabaseBinding.class))).thenAnswer(invocation -> {
            LakeOdsDatabaseBinding binding = invocation.getArgument(0);
            stored.set(binding);
            return 1;
        });
        when(bindingDao.queryByIdIncludingDeleted(anyLong())).thenAnswer(invocation -> stored.get());
        when(dorisClient.databaseExists("ods_unit_system_orders")).thenReturn(false, true);
        stubCoordinatorFor(stored);

        LakeOdsDatabaseCreateDTO request = new LakeOdsDatabaseCreateDTO();
        request.setCustomName("orders");

        var result = service.create(7L, request);

        assertEquals("ods_unit_system_orders", result.getDatabaseName());
        assertEquals(LakeResourceStatus.READY, result.getResourceStatus());
        assertEquals("unit", stored.get().getUnitCode());
        assertEquals("system", stored.get().getSystemCode());
        assertEquals("ods_unit_system_orders", stored.get().getDatabaseName());
        assertEquals(12, stored.get().getCreateUserId());
        verify(dorisClient).createDatabase("ods_unit_system_orders");
    }

    @Test
    void retryReadsActualDatabaseFirstAndDoesNotRecreateAnExistingDatabase() {
        LakeOdsDatabaseBinding binding = binding(31L, LakeResourceStatus.ERROR, false);
        when(bindingDao.queryByIdIncludingDeleted(31L)).thenReturn(binding);
        when(dorisClient.databaseExists("ods_unit_system_orders")).thenReturn(true, true);
        stubCoordinatorFor(new AtomicReference<>(binding));

        var result = service.retry(31L);

        assertEquals(LakeResourceStatus.READY, result.getResourceStatus());
        verify(dorisClient, never()).createDatabase(any());
        verify(dorisClient, org.mockito.Mockito.times(2)).databaseExists("ods_unit_system_orders");
    }

    @Test
    void createRejectsANameReservedByAnotherLocalBinding() {
        LakeOdsDatabaseBinding target = binding(35L, LakeResourceStatus.READY, false);
        target.setSourceDataSourceId(8L);
        when(bindingDao.queryBySourceDataSourceId(7L)).thenReturn(null);
        when(bindingDao.queryByLakeDataSourceIdAndDatabaseNameIncludingDeleted(
                99L, "ods_unit_system_orders")).thenReturn(target);

        LakeOdsDatabaseCreateDTO request = new LakeOdsDatabaseCreateDTO();
        request.setCustomName("orders");

        LakeServiceException exception = assertThrows(LakeServiceException.class,
                () -> service.create(7L, request));

        assertEquals(LakeErrorCode.LAKE_DATABASE_NAME_CONFLICT, exception.getLakeErrorCode());
        verify(dorisClientProvider, never()).get(anyLong());
    }

    @Test
    void reconcileClassifiesMissingDorisDatabaseWithoutWritingOnDetailRead() {
        LakeOdsDatabaseBinding binding = binding(32L, LakeResourceStatus.READY, false);
        when(bindingDao.queryByIdIncludingDeleted(32L)).thenReturn(binding);
        when(dorisClient.databaseExists("ods_unit_system_orders")).thenReturn(false);
        stubCoordinatorFor(new AtomicReference<>(binding));
        doAnswer(invocation -> {
            binding.setResourceStatus(LakeResourceStatus.MISSING);
            binding.setErrorCode(LakeErrorCode.LAKE_DATABASE_MISSING);
            return true;
        }).when(coordinator).fail(any(), any(), any());

        var result = service.reconcile(32L);

        assertEquals(LakeResourceStatus.MISSING, result.getResourceStatus());
        assertEquals(LakeErrorCode.LAKE_DATABASE_MISSING, result.getErrorCode());
        verify(coordinator, never()).finalizeSuccess(any(), any());
        service.detail(32L);
        verify(bindingDao, org.mockito.Mockito.times(3)).queryByIdIncludingDeleted(32L);
        verify(coordinator, org.mockito.Mockito.times(1)).fail(any(), any(), any());
    }

    @Test
    void deleteRejectsActiveTableMappingBeforeCallingDoris() {
        LakeOdsDatabaseBinding binding = binding(33L, LakeResourceStatus.READY, false);
        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.setDeleted(false);
        when(bindingDao.queryByIdIncludingDeleted(33L)).thenReturn(binding);
        when(tableMappingDao.queryByOdsDatabaseBindingId(33L)).thenReturn(List.of(mapping));

        LakeServiceException exception = assertThrows(LakeServiceException.class,
                () -> service.delete(33L));

        assertEquals(LakeErrorCode.LAKE_DATABASE_IN_USE, exception.getLakeErrorCode());
        verify(dorisClientProvider, never()).get(anyLong());
        verify(coordinator, never()).begin(any(LakeOperationIntent.class));
    }

    @Test
    void deleteRejectsActiveNamespaceRelationBeforeCallingDoris() {
        LakeOdsDatabaseBinding binding = binding(34L, LakeResourceStatus.READY, false);
        LakeJobRelation relation = new LakeJobRelation();
        relation.setRelationScope(LakeRelationScope.NAMESPACE);
        relation.setRelationStatus(LakeRelationStatus.ACTIVE);
        when(bindingDao.queryByIdIncludingDeleted(34L)).thenReturn(binding);
        when(tableMappingDao.queryByOdsDatabaseBindingId(34L)).thenReturn(List.of());
        when(jobRelationDao.queryByOdsDatabaseBindingId(34L)).thenReturn(List.of(relation));

        LakeServiceException exception = assertThrows(LakeServiceException.class,
                () -> service.delete(34L));

        assertEquals(LakeErrorCode.LAKE_DATABASE_IN_USE, exception.getLakeErrorCode());
        verify(dorisClientProvider, never()).get(anyLong());
        verify(coordinator, never()).begin(any(LakeOperationIntent.class));
    }

    @Test
    void pageFiltersResourceStatusBeforePagingAndPreservesDaoTotalAcrossPages() {
        LakePhysicalDataSourcePageDTO request = new LakePhysicalDataSourcePageDTO();
        request.setPageNo(2);
        request.setPageSize(1);
        request.setResourceStatus(LakeResourceStatus.READY.getCode());

        Page<DataSource> filteredPage = new Page<>(2, 1);
        filteredPage.setTotal(2);
        filteredPage.setRecords(List.of(source(8L, "orders-2", 11L, DbType.MYSQL)));
        when(bindingDao.querySourceDataSourceIdsByResourceStatus(LakeResourceStatus.READY))
                .thenReturn(List.of(7L, 8L));
        when(dataSourceDao.queryPageByDataSourceIds(any(), eq(List.of(7L, 8L))))
                .thenReturn(filteredPage);

        var result = service.page(request);

        assertEquals(2, result.getData().getPagination().getTotal());
        assertEquals(2, result.getData().getPagination().getPageNo());
        assertEquals(1, result.getData().getPagination().getPageSize());
        assertEquals(1, result.getData().getBizData().size());
        verify(dataSourceDao).queryPageByDataSourceIds(any(), eq(List.of(7L, 8L)));
        verify(dataSourceDao, never()).queryPage(any());
    }

    private void stubCoordinatorFor(AtomicReference<LakeOdsDatabaseBinding> binding) {
        when(coordinator.begin(any(LakeOperationIntent.class))).thenAnswer(invocation -> {
            LakeOperationIntent intent = invocation.getArgument(0);
            LakeOdsDatabaseBinding current = binding.get();
            return new LakeOperationHandle(
                    1L, intent.getResourceType(), intent.getResourceId(),
                    current.getGeneration(), "operation-token", current.getLockVersion() + 1);
        });
        doAnswer(invocation -> {
            var operation = invocation.<org.apache.seatunnel.web.api.lake.operation.LakeExternalOperation<?>>getArgument(1);
            Object externalResult = operation.execute();
            return new LakeOperationExecution<>(invocation.getArgument(0), externalResult);
        }).when(coordinator).execute(any(), any());
        lenient().doAnswer(invocation -> {
            LakeOdsDatabaseBinding current = binding.get();
            current.setResourceStatus(LakeResourceStatus.READY);
            current.setOperationToken(null);
            current.setDeleted(false);
            return true;
        }).when(coordinator).finalizeSuccess(any(), any());
    }

    private static LakeOdsDatabaseBinding binding(
            Long id, LakeResourceStatus status, boolean deleted) {
        LakeOdsDatabaseBinding binding = new LakeOdsDatabaseBinding();
        binding.setId(id);
        binding.setLakeDataSourceId(99L);
        binding.setSourceDataSourceId(7L);
        binding.setUnitCode("unit");
        binding.setSystemCode("system");
        binding.setDatabaseName("ods_unit_system_orders");
        binding.setResourceStatus(status);
        binding.setGeneration(1);
        binding.setLockVersion(1);
        binding.setDeleted(deleted);
        return binding;
    }

    private static DataSource source(Long id, String name, Long businessSystemId, DbType dbType) {
        DataSource source = new DataSource();
        source.setId(id);
        source.setName(name);
        source.setBusinessSystemId(businessSystemId);
        source.setDbType(dbType);
        source.setStatus(DataSourceLifecycleStatus.ENABLED);
        return source;
    }
}
