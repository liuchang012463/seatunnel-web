package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabase;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.BusinessSystem;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.DataSourceUnit;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceTopologyNodeVO;
import org.apache.seatunnel.web.spi.enums.DataSourceTopologyNodeType;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataSourceTopologyServiceTest {

    @Mock private DataSourceDao dataSourceDao;
    @Mock private DataSourceUnitDao dataSourceUnitDao;
    @Mock private BusinessSystemDao businessSystemDao;
    @Mock private MetadataBindingDao metadataBindingDao;
    @Mock private OpenMetadataClient openMetadataClient;

    @Test
    void initialTreeIsShallowAndDoesNotFetchOpenMetadataTables() {
        DataSourceUnit unit = unit();
        BusinessSystem system = system();
        DataSource source = source();
        when(dataSourceUnitDao.queryAll()).thenReturn(List.of(unit));
        when(businessSystemDao.queryAll()).thenReturn(List.of(system));
        when(dataSourceDao.queryAll()).thenReturn(List.of(source));

        List<DataSourceTopologyNodeVO> result = service().tree(null, null, null);

        assertEquals(DataSourceTopologyNodeType.UNIT, result.get(0).getNodeType());
        assertEquals(DataSourceTopologyNodeType.BUSINESS_SYSTEM,
                result.get(0).getChildren().get(0).getNodeType());
        assertEquals(DataSourceTopologyNodeType.DATA_SOURCE,
                result.get(0).getChildren().get(0).getChildren().get(0).getNodeType());
        verify(openMetadataClient, never()).listDatabasesPage(any(), any(Integer.class), any());
    }

    @Test
    void dataSourceChildrenLoadDatabasesOnlyOnDemand() {
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(metadataBindingDao.queryByDataSourceId(42L)).thenReturn(binding());
        when(openMetadataClient.listDatabasesPage(eq("st_ds_42"), any(Integer.class), eq(null)))
                .thenReturn(new OpenMetadataPage<>(List.of(
                        new OpenMetadataDatabase("db", "st_ds_42.orders", "st_ds_42")), 1L, null));

        List<DataSourceTopologyNodeVO> result = service().children(
                DataSourceTopologyNodeType.DATA_SOURCE, "42");

        assertEquals(1, result.size());
        assertEquals(DataSourceTopologyNodeType.DATABASE, result.get(0).getNodeType());
        assertEquals("st_ds_42.orders", result.get(0).getId());
    }

    private DataSourceTopologyService service() {
        return new DataSourceTopologyService(dataSourceDao, dataSourceUnitDao, businessSystemDao,
                metadataBindingDao, openMetadataClient);
    }

    private static DataSourceUnit unit() {
        DataSourceUnit unit = new DataSourceUnit();
        unit.setId(7L);
        unit.setUnitName("市局");
        unit.setStatus(1);
        return unit;
    }

    private static BusinessSystem system() {
        BusinessSystem system = new BusinessSystem();
        system.setId(9L);
        system.setUnitId(7L);
        system.setSystemName("订单系统");
        system.setStatus(1);
        return system;
    }

    private static DataSource source() {
        DataSource source = new DataSource();
        source.setId(42L);
        source.setName("Orders");
        source.setBusinessSystemId(9L);
        source.setDbType(DbType.MYSQL);
        source.setStatus(DataSourceLifecycleStatus.ENABLED);
        return source;
    }

    private static MetadataSourceBinding binding() {
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setDataSourceId(42L);
        binding.setDesiredState(MetadataDesiredState.ACTIVE);
        binding.setSyncStatus(MetadataSyncStatus.READY);
        binding.setOmServiceFqn("st_ds_42");
        return binding;
    }
}
