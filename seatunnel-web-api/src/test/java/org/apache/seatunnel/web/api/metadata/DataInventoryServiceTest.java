package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataColumn;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabase;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabaseSchema;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTable;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTableProfile;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataRunStatus;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.BusinessSystem;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.DataSourceUnit;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.bean.vo.DataInventorySummaryVO;
import org.apache.seatunnel.web.spi.bean.dto.DataInventoryFilterDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataInventoryServiceTest {

    @Mock private DataSourceDao dataSourceDao;
    @Mock private DataSourceUnitDao dataSourceUnitDao;
    @Mock private BusinessSystemDao businessSystemDao;
    @Mock private MetadataBindingDao metadataBindingDao;
    @Mock private OpenMetadataClient openMetadataClient;

    @Test
    void aggregatesMasterDataAndOpenMetadataPagingProfileMetrics() {
        DataSource source = source(42L, org.apache.seatunnel.web.spi.enums.DbType.MYSQL);
        DataSourceUnit unit = new DataSourceUnit();
        unit.setId(7L);
        unit.setUnitName("市局");
        unit.setStatus(1);
        BusinessSystem system = new BusinessSystem();
        system.setId(9L);
        system.setUnitId(7L);
        system.setSystemName("订单系统");
        system.setStatus(1);
        MetadataSourceBinding binding = binding(42L);
        OpenMetadataDatabase database = new OpenMetadataDatabase("db", "st_ds_42.orders", "st_ds_42");
        OpenMetadataDatabaseSchema schema = schema("st_ds_42.orders.public");
        OpenMetadataTable table = table();
        OpenMetadataTableProfile profile = new OpenMetadataTableProfile();
        profile.setTimestamp(1700000000000L);
        profile.setRowCount(100L);
        table.setProfile(profile);

        when(dataSourceDao.queryAll()).thenReturn(List.of(source));
        when(dataSourceUnitDao.queryAll()).thenReturn(List.of(unit));
        when(businessSystemDao.queryAll()).thenReturn(List.of(system));
        when(metadataBindingDao.queryAll()).thenReturn(List.of(binding));
        when(openMetadataClient.listDatabasesPage(eq("st_ds_42"), any(Integer.class), eq(null)))
                .thenReturn(new OpenMetadataPage<>(List.of(database), 2L, null));
        when(openMetadataClient.listSchemasPage(eq("st_ds_42.orders"), any(Integer.class), eq(null)))
                .thenReturn(new OpenMetadataPage<>(List.of(schema), 1L, null));
        when(openMetadataClient.listTablesPage(eq("st_ds_42.orders.public"), eq(true), any(Integer.class), eq(null)))
                .thenReturn(new OpenMetadataPage<>(List.of(table), 3L, null));

        DataInventorySummaryVO result = service().summary(new DataInventoryFilterDTO());

        assertEquals(1L, result.getUnitCount());
        assertEquals(1L, result.getBusinessSystemCount());
        assertEquals(1L, result.getDataSourceCount());
        assertEquals(2L, result.getDatabaseCount());
        assertEquals(1L, result.getSchemaCount());
        assertEquals(3L, result.getTableCount());
        assertEquals(1L, result.getColumnCount());
        assertEquals(1L, result.getProfiledDatabaseCount());
        assertEquals(1L, result.getProfiledTableCount());
        assertEquals(100L, result.getKnownRowCount());
    }

    @Test
    void skipsUnsupportedSourceWithoutCallingOpenMetadata() {
        when(dataSourceDao.queryAll()).thenReturn(List.of(source(42L, org.apache.seatunnel.web.spi.enums.DbType.ORACLE)));
        when(dataSourceUnitDao.queryAll()).thenReturn(List.of());
        when(businessSystemDao.queryAll()).thenReturn(List.of());
        when(metadataBindingDao.queryAll()).thenReturn(List.of(binding(42L)));

        assertEquals(1L, service().summary(new DataInventoryFilterDTO()).getDataSourceCount());
        verify(openMetadataClient, never()).listDatabasesPage(any(), any(Integer.class), any());
    }

    private DataInventoryService service() {
        return new DataInventoryService(dataSourceDao, dataSourceUnitDao, businessSystemDao,
                metadataBindingDao, openMetadataClient);
    }

    private static DataSource source(Long id, org.apache.seatunnel.web.spi.enums.DbType type) {
        DataSource source = new DataSource();
        source.setId(id);
        source.setName("Orders");
        source.setDbType(type);
        source.setBusinessSystemId(9L);
        source.setStatus(DataSourceLifecycleStatus.ENABLED);
        return source;
    }

    private static MetadataSourceBinding binding(Long dataSourceId) {
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setDataSourceId(dataSourceId);
        binding.setDesiredState(MetadataDesiredState.ACTIVE);
        binding.setSyncStatus(MetadataSyncStatus.READY);
        binding.setOmServiceFqn("st_ds_" + dataSourceId);
        binding.setProfileStatus(MetadataRunStatus.SUCCESS);
        return binding;
    }

    private static OpenMetadataDatabaseSchema schema(String fqn) {
        OpenMetadataDatabaseSchema schema = new OpenMetadataDatabaseSchema();
        schema.setId("schema");
        schema.setName("public");
        schema.setFullyQualifiedName(fqn);
        schema.setDatabaseFullyQualifiedName("st_ds_42.orders");
        schema.setServiceFullyQualifiedName("st_ds_42");
        return schema;
    }

    private static OpenMetadataTable table() {
        OpenMetadataTable table = new OpenMetadataTable();
        table.setId("table");
        table.setName("orders");
        table.setFullyQualifiedName("st_ds_42.orders.public.orders");
        table.setDatabaseFullyQualifiedName("st_ds_42.orders");
        table.setSchemaFullyQualifiedName("st_ds_42.orders.public");
        table.setServiceFullyQualifiedName("st_ds_42");
        OpenMetadataColumn column = new OpenMetadataColumn();
        column.setName("id");
        column.setDataType("BIGINT");
        table.setColumns(List.of(column));
        return table;
    }
}
