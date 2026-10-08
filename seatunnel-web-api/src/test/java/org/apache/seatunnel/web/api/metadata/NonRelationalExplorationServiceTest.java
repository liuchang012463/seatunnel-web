package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorAdapter;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorRegistry;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataResource;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataResourceDetail;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataResourceField;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceResourceDetailVO;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceResourcePageVO;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NonRelationalExplorationServiceTest {

    @Mock private DataSourceDao dataSourceDao;
    @Mock private MetadataBindingDao metadataBindingDao;
    @Mock private MetadataConnectorRegistry connectorRegistry;
    @Mock private MetadataConnectorAdapter adapter;
    @Mock private OpenMetadataClient openMetadataClient;

    @Test
    void listsTopicAssetsForAKafkaDataSource() {
        stubSource(DbType.KAFKA);
        when(connectorRegistry.find(DbType.KAFKA)).thenReturn(Optional.of(adapter));
        when(adapter.resourceTypes()).thenReturn(List.of(OmResourceType.TOPIC));
        when(openMetadataClient.listResourcesPage(eq(OmResourceType.TOPIC), eq("st_ds_42"), anyInt(), any()))
                .thenReturn(new OpenMetadataPage<>(
                        List.of(new OpenMetadataResource(
                                "topic-id", "orders", "st_ds_42.orders", "topic",
                                "order events", 3, List.of("PII.Sensitive"), "st_ds_42")),
                        1L,
                        null));

        DataSourceResourcePageVO page = service().listResources(42L, null, 100);

        assertEquals(List.of("topic"), page.getEntityTypes());
        assertFalse(page.isTruncated());
        assertEquals(1, page.getResources().size());
        assertEquals("orders", page.getResources().get(0).getName());
        assertEquals("主题", page.getResources().get(0).getEntityLabel());
        assertEquals(3, page.getResources().get(0).getFieldCount());
    }

    @Test
    void rejectsResourceTypesTheDataSourceDoesNotExpose() {
        stubSourceAndAdapter(DbType.KAFKA, List.of(OmResourceType.TOPIC));

        assertThrows(RuntimeException.class, () -> service().listResources(42L, "container", 100));
        verify(openMetadataClient, never()).listResourcesPage(any(), anyString(), anyInt(), any());
    }

    @Test
    void rejectsDatabaseDataSourcesThatHaveNoNonRelationalAssets() {
        stubSourceAndAdapter(DbType.MYSQL, List.of());

        assertThrows(RuntimeException.class, () -> service().listResources(42L, null, 100));
    }

    @Test
    void rejectsListingBeforeTheBindingIsReady() {
        DataSource source = source(DbType.KAFKA);
        when(dataSourceDao.queryById(42L)).thenReturn(source);
        when(connectorRegistry.find(DbType.KAFKA)).thenReturn(Optional.of(adapter));
        when(adapter.resourceTypes()).thenReturn(List.of(OmResourceType.TOPIC));
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setDesiredState(MetadataDesiredState.ACTIVE);
        binding.setSyncStatus(MetadataSyncStatus.PENDING);
        when(metadataBindingDao.queryByDataSourceId(42L)).thenReturn(binding);

        assertThrows(RuntimeException.class, () -> service().listResources(42L, null, 100));
    }

    @Test
    void returnsSchemaAndSamplePayloadForOneAsset() {
        stubSource(DbType.KAFKA);
        when(connectorRegistry.find(DbType.KAFKA)).thenReturn(Optional.of(adapter));
        when(adapter.resourceTypes()).thenReturn(List.of(OmResourceType.TOPIC));
        when(openMetadataClient.getResourceDetail(OmResourceType.TOPIC, "topic-id"))
                .thenReturn(new OpenMetadataResourceDetail(
                        new OpenMetadataResource(
                                "topic-id", "orders", "st_ds_42.orders", "topic",
                                "order events", 1, List.of(), "st_ds_42"),
                        List.of(new OpenMetadataResourceField("id", "INT", null, List.of())),
                        true,
                        List.of(),
                        List.of(),
                        List.of("{\"id\":1}")));

        DataSourceResourceDetailVO detail = service().getResourceDetail(42L, "topic", "topic-id");

        assertTrue(detail.isSampleDataAvailable());
        assertEquals(1, detail.getFields().size());
        assertEquals("id", detail.getFields().get(0).getName());
        assertEquals(1, detail.getMessages().size());
    }

    @Test
    void rejectsAnAssetOwnedByAnotherService() {
        stubSource(DbType.KAFKA);
        when(connectorRegistry.find(DbType.KAFKA)).thenReturn(Optional.of(adapter));
        when(adapter.resourceTypes()).thenReturn(List.of(OmResourceType.TOPIC));
        when(openMetadataClient.getResourceDetail(OmResourceType.TOPIC, "topic-id"))
                .thenReturn(new OpenMetadataResourceDetail(
                        new OpenMetadataResource(
                                "topic-id", "orders", "other_service.orders", "topic",
                                null, null, List.of(), "other_service"),
                        List.of(), false, List.of(), List.of(), List.of()));

        assertThrows(RuntimeException.class, () -> service().getResourceDetail(42L, "topic", "topic-id"));
    }

    private void stubSource(DbType dbType) {
        when(dataSourceDao.queryById(42L)).thenReturn(source(dbType));
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setDataSourceId(42L);
        binding.setDesiredState(MetadataDesiredState.ACTIVE);
        binding.setSyncStatus(MetadataSyncStatus.READY);
        binding.setOmServiceFqn("st_ds_42");
        when(metadataBindingDao.queryByDataSourceId(42L)).thenReturn(binding);
    }

    /** Active data source plus adapter, without the binding that later stages require. */
    private void stubSourceAndAdapter(DbType dbType, List<OmResourceType> resourceTypes) {
        when(dataSourceDao.queryById(42L)).thenReturn(source(dbType));
        when(connectorRegistry.find(dbType)).thenReturn(Optional.of(adapter));
        when(adapter.resourceTypes()).thenReturn(resourceTypes);
    }

    private static DataSource source(DbType dbType) {
        DataSource source = new DataSource();
        source.setId(42L);
        source.setName("events");
        source.setDbType(dbType);
        source.setStatus(DataSourceLifecycleStatus.ENABLED);
        return source;
    }

    private NonRelationalExplorationService service() {
        OpenMetadataProperties properties = new OpenMetadataProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:18585/api");
        properties.setToken("test-token");
        return new NonRelationalExplorationService(
                OpenMetadataConfigResolver.fixed(properties),
                dataSourceDao,
                metadataBindingDao,
                connectorRegistry,
                openMetadataClient);
    }
}
