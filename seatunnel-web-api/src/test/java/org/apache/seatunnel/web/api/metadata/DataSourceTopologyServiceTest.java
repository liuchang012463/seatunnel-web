package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabase;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.BusinessSystem;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.DataSourceUnit;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceLineageVO;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceTopologyNodeVO;
import org.apache.seatunnel.web.spi.enums.DataSourceTopologyNodeType;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openmetadata.schema.api.lineage.EsLineageData;
import org.openmetadata.schema.api.lineage.RelationshipRef;
import org.openmetadata.schema.type.Edge;
import org.openmetadata.schema.type.EntityLineage;
import org.openmetadata.schema.type.EntityReference;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void serviceGraphKeepsDirectionAndCountsWithinBoundDataSources() {
        UUID ordersServiceId = UUID.randomUUID();
        MetadataSourceBinding orders = binding();
        orders.setOmServiceId(ordersServiceId.toString());
        MetadataSourceBinding warehouse = binding();
        warehouse.setDataSourceId(43L);
        warehouse.setOmServiceFqn("st_ds_43");
        DataSource target = source();
        target.setId(43L);
        target.setName("Warehouse");
        DataSource revoked = source();
        revoked.setId(44L);
        revoked.setStatus(DataSourceLifecycleStatus.REVOKED);
        when(dataSourceDao.queryAll()).thenReturn(List.of(source(), target, revoked));
        when(metadataBindingDao.queryByDataSourceIds(Set.of(42L, 43L))).thenReturn(List.of(orders, warehouse));

        // The two ends exercise ID matching and the exact FQN fallback.
        var from = new RelationshipRef().withId(ordersServiceId);
        var to = new RelationshipRef().withFullyQualifiedName("st_ds_43");
        var outside = new RelationshipRef().withFullyQualifiedName("st_ds_44");
        var flow = new EsLineageData().withFromEntity(from).withToEntity(to).withAssetEdges(2);
        when(openMetadataClient.getServiceLineage()).thenReturn(List.of(flow, flow,
                new EsLineageData().withFromEntity(to).withToEntity(from).withAssetEdges(1),
                new EsLineageData().withFromEntity(from).withToEntity(outside).withAssetEdges(5)));

        DataSourceLineageVO graph = service().lineage();

        assertNotNull(graph.readAt());
        assertEquals(List.of("42", "43"), graph.nodes().stream().map(DataSourceLineageVO.Node::id).toList());
        assertEquals(List.of(new DataSourceLineageVO.Link("42", "43", 2),
                new DataSourceLineageVO.Link("43", "42", 1)), graph.edges());
    }

    @Test
    void tableLineageKeepsSameSourceRelationsAndFiltersUnboundAssets() {
        UUID tableId = UUID.randomUUID();
        UUID sameSource = UUID.randomUUID();
        UUID downstream = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        DataSource warehouse = source();
        warehouse.setId(43L);
        warehouse.setName("Warehouse");
        MetadataSourceBinding warehouseBinding = binding();
        warehouseBinding.setDataSourceId(43L);
        warehouseBinding.setOmServiceFqn("st_ds_43");
        when(dataSourceDao.queryAll()).thenReturn(List.of(source(), warehouse));
        when(metadataBindingDao.queryByDataSourceIds(Set.of(42L, 43L))).thenReturn(List.of(binding(), warehouseBinding));
        when(openMetadataClient.getTableLineage(tableId)).thenReturn(new EntityLineage()
                .withEntity(table(tableId, "st_ds_42", "orders"))
                .withNodes(List.of(table(sameSource, "st_ds_42", "raw_orders"),
                        table(downstream, "st_ds_43", "warehouse_orders"),
                        table(outside, "st_ds_420", "external_orders")))
                .withUpstreamEdges(List.of(new Edge().withFromEntity(sameSource).withToEntity(tableId),
                        new Edge().withFromEntity(outside).withToEntity(tableId)))
                .withDownstreamEdges(List.of(new Edge().withFromEntity(tableId).withToEntity(downstream))));

        var lineage = service().tableLineage(42L, tableId);

        assertEquals(List.of("raw_orders"), lineage.upstream().stream().map(DataSourceLineageVO.Asset::name).toList());
        assertEquals(List.of("warehouse_orders"),
                lineage.downstream().stream().map(DataSourceLineageVO.Asset::name).toList());
        assertThrows(ServiceException.class, () -> service().tableLineage(43L, tableId));
    }

    private static EntityReference table(UUID id, String service, String name) {
        return new EntityReference().withId(id).withType("table").withName(name)
                .withFullyQualifiedName(service + ".db.schema." + name);
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
