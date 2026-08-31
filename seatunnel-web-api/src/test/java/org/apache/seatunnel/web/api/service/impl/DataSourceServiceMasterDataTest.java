package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.service.MetadataBindingCommandService;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.dao.entity.BusinessSystem;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.DataSourceUnit;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.dao.repository.JobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionDao;
import org.apache.seatunnel.web.spi.bean.dto.DataSourceDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceVO;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class DataSourceServiceMasterDataTest {

    @Mock
    private DataSourceDao dataSourceDao;

    @Mock
    private BusinessSystemDao businessSystemDao;

    @Mock
    private DataSourceUnitDao dataSourceUnitDao;

    @Mock
    private MetadataBindingCommandService metadataBindingCommandService;

    @Mock
    private MetadataBindingDao metadataBindingDao;

    @Mock
    private JobDefinitionDao jobDefinitionDao;

    @Mock
    private StreamingJobDefinitionDao streamingJobDefinitionDao;

    @Mock
    private LakeOdsDatabaseBindingDao lakeOdsDatabaseBindingDao;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @InjectMocks
    private DataSourceServiceImpl service;

    @Test
    void rejectsCreateWithoutCanonicalBusinessSystem() {
        DataSourceDTO dto = new DataSourceDTO();
        dto.setName("orders");
        dto.setDataSourceUnit("free text must not satisfy ownership");
        dto.setDbType(DbType.MYSQL);
        dto.setConnectionParams("{}");

        assertThrows(ServiceException.class, () -> service.createDataSource(dto));
        verify(dataSourceDao, never()).insert(any(DataSource.class));
    }

    @Test
    void mapsCanonicalNamesAndHistoricalUnassignedRows() {
        DataSource canonical = new DataSource();
        canonical.setId(1L);
        canonical.setName("orders");
        canonical.setBusinessSystemId(9L);
        canonical.setStatus(DataSourceLifecycleStatus.ENABLED);
        canonical.setDbType(DbType.MYSQL);

        DataSource historical = new DataSource();
        historical.setId(2L);
        historical.setName("legacy");
        historical.setStatus(DataSourceLifecycleStatus.ENABLED);
        historical.setDbType(DbType.MYSQL);

        BusinessSystem system = new BusinessSystem();
        system.setId(9L);
        system.setUnitId(7L);
        system.setSystemCode("OMS");
        system.setSystemName("Order System");

        DataSourceUnit unit = new DataSourceUnit();
        unit.setId(7L);
        unit.setUnitCode("HQ");
        unit.setUnitName("Headquarters");

        Page<DataSource> page = new Page<>(1, 10);
        page.setRecords(List.of(canonical, historical));
        page.setTotal(2);
        when(dataSourceDao.queryPage(any(DataSourceDTO.class), isNull())).thenReturn(page);
        when(businessSystemDao.queryByIds(List.of(9L))).thenReturn(List.of(system));
        when(dataSourceUnitDao.queryByIds(List.of(7L))).thenReturn(List.of(unit));
        when(metadataBindingDao.queryByDataSourceIds(List.of(1L, 2L))).thenReturn(List.of());

        PaginationResult<DataSourceVO> result = service.queryDataSourceListPaging(new DataSourceDTO());

        assertEquals("Headquarters", result.getData().getBizData().get(0).getDataSourceUnit());
        assertEquals("Order System", result.getData().getBizData().get(0).getBusinessSystemName());
        assertEquals(7L, result.getData().getBizData().get(0).getUnitId());
        assertEquals("待归属", result.getData().getBizData().get(1).getDataSourceUnit());
        assertEquals("NOT_INITIALIZED", result.getData().getBizData().get(0).getMetadataSyncStatus());
    }

    @Test
    void deletionKeepsTheLocalSourceUntilTheReconcilerCleansOpenMetadata() {
        DataSource source = new DataSource();
        source.setId(42L);
        source.setStatus(DataSourceLifecycleStatus.ENABLED);
        when(dataSourceDao.queryById(42L)).thenReturn(source);
        when(jobDefinitionDao.selectReferencedDatasourceIds(List.of(42L))).thenReturn(List.of());
        when(streamingJobDefinitionDao.selectReferencedDatasourceIds(List.of(42L))).thenReturn(List.of());
        when(currentUserProvider.getCurrentUserId()).thenReturn(1);

        service.delete(42L);

        verify(metadataBindingCommandService).markDeleted(42L);
        verify(dataSourceDao, never()).deleteById(42L);
        ArgumentCaptor<DataSource> update = ArgumentCaptor.forClass(DataSource.class);
        verify(dataSourceDao).updateById(update.capture());
        assertEquals(DataSourceLifecycleStatus.REVOKED, update.getValue().getStatus());
    }

    @Test
    void deletionIsBlockedWhileSourceIsBoundToAnActiveLakeDatabase() {
        DataSource source = new DataSource();
        source.setId(42L);
        source.setStatus(DataSourceLifecycleStatus.ENABLED);
        when(dataSourceDao.queryById(42L)).thenReturn(source);
        when(jobDefinitionDao.selectReferencedDatasourceIds(List.of(42L))).thenReturn(List.of());
        when(streamingJobDefinitionDao.selectReferencedDatasourceIds(List.of(42L))).thenReturn(List.of());
        when(lakeOdsDatabaseBindingDao.existsActiveBySourceDataSourceId(42L)).thenReturn(true);

        LakeServiceException exception = assertThrows(LakeServiceException.class,
                () -> service.delete(42L));

        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, exception.getLakeErrorCode());
        verify(metadataBindingCommandService, never()).markDeleted(42L);
        verify(dataSourceDao, never()).updateById(any(DataSource.class));
    }

    @Test
    void ownershipBackfillDoesNotRejectAJobReferencedSource() {
        DataSource source = new DataSource();
        source.setId(42L);
        source.setStatus(DataSourceLifecycleStatus.ENABLED);
        source.setBusinessSystemId(null);

        BusinessSystem system = new BusinessSystem();
        system.setId(9L);
        system.setUnitId(7L);
        system.setStatus(1);

        DataSourceUnit unit = new DataSourceUnit();
        unit.setId(7L);
        unit.setStatus(1);

        when(dataSourceDao.queryById(42L)).thenReturn(source);
        when(businessSystemDao.queryById(9L)).thenReturn(system);
        when(dataSourceUnitDao.queryById(7L)).thenReturn(unit);
        when(currentUserProvider.getCurrentUserId()).thenReturn(1);
        when(dataSourceDao.updateById(any(DataSource.class))).thenAnswer(invocation -> {
            DataSource update = invocation.getArgument(0);
            source.setBusinessSystemId(update.getBusinessSystemId());
            return true;
        });

        DataSource assigned = service.assignBusinessSystem(42L, 9L);

        assertEquals(9L, assigned.getBusinessSystemId());
        verify(dataSourceDao).updateById(any(DataSource.class));
        verify(metadataBindingCommandService).markConfigurationChanged(42L);
        verify(jobDefinitionDao, never()).existsByDatasourceId(42L);
        verify(streamingJobDefinitionDao, never()).existsByDatasourceId(42L);
    }
}
