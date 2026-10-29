package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorAdapter;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorRegistry;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataAsset;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataAssetDetail;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataAssetField;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataAssetSample;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationAssetDetailVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationAssetPageVO;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetExplorationServiceTest {

    @Mock private DataSourceDao dataSourceDao;
    @Mock private MetadataBindingDao metadataBindingDao;
    @Mock private MetadataConnectorRegistry connectorRegistry;
    @Mock private MetadataConnectorAdapter adapter;
    @Mock private OpenMetadataClient openMetadataClient;

    @Test
    void apiCollectionParentProjectionAvoidsTheUnsupportedParentField() {
        // APICollection has no parent property; requesting it makes OpenMetadata
        // reject the detail read, so the projection must stay service-only.
        assertEquals("service", ExplorationAssetType.API_COLLECTION.parentFields());
        assertEquals("service", ExplorationAssetType.API_COLLECTION.childrenFields());
    }

    @Test
    void listsTopicsWithLabelsAndNoChildren() {
        stubSource(DbType.KAFKA, List.of(ExplorationAssetType.TOPIC), true);
        when(openMetadataClient.listAssetsPage(
                eq(ExplorationAssetType.TOPIC), eq("st_ds_42"), isNull(), eq(false), anyInt(), any()))
                .thenReturn(new OpenMetadataPage<>(
                        List.of(topic("topic-1", "orders.created", 4)), 1L, null));

        DataExplorationAssetPageVO page =
                service().listAssets(42L, null, null, 1, 50, null);

        assertEquals(1, page.getRecords().size());
        assertEquals("TOPIC", page.getRecords().get(0).getAssetType());
        assertEquals("主题", page.getRecords().get(0).getAssetLabel());
        assertEquals(4, page.getRecords().get(0).getFieldCount());
        assertFalse(page.getRecords().get(0).isHasChildren());
        assertEquals(1, page.getTotal());
        assertFalse(page.isTruncated());
        assertTrue(page.getBreadcrumbs().isEmpty());
    }

    @Test
    void listsDirectoryChildrenAndEnrichesFilesFromTheFileCollection() {
        stubSource(DbType.SFTP, List.of(ExplorationAssetType.DIRECTORY, ExplorationAssetType.FILE), true);
        OpenMetadataAsset directory = asset(
                "dir-1", "data", "st_ds_42.data", "directory", null, null, "st_ds_42",
                null, null, null, null);
        OpenMetadataAsset childDirectory = asset(
                "dir-2", "2026", "st_ds_42.data.2026", "directory", null, null, "st_ds_42",
                "dir-1", "directory", "st_ds_42.data", null);
        OpenMetadataAsset childFile = asset(
                "file-1", "orders.csv", "st_ds_42.data.orders.csv", "file", 5, null,
                "st_ds_42", "dir-1", "directory", "st_ds_42.data", 2048L);
        when(openMetadataClient.getAssetDetail(eq(ExplorationAssetType.DIRECTORY), eq("dir-1"), anyBoolean(), anyString()))
                .thenReturn(new OpenMetadataAssetDetail(
                        directory, List.of(), List.of(), List.of(), null,
                        false, false, List.of(childDirectory), Map.of()));
        when(openMetadataClient.listFilesInDirectory(eq("st_ds_42.data"), anyInt(), any()))
                .thenReturn(new OpenMetadataPage<>(List.of(childFile), 1L, null));

        DataExplorationAssetPageVO page =
                service().listAssets(42L, "dir-1", "DIRECTORY", 1, 50, null);

        assertEquals(2, page.getRecords().size());
        assertEquals("DIRECTORY", page.getRecords().get(0).getAssetType());
        assertEquals("FILE", page.getRecords().get(1).getAssetType());
        assertEquals(5, page.getRecords().get(1).getFieldCount());
        assertEquals(2048L, page.getRecords().get(1).getSizeInByte());
        assertEquals("data", page.getParent().getName());
        assertEquals(1, page.getBreadcrumbs().size());
        verify(openMetadataClient).listFilesInDirectory(eq("st_ds_42.data"), anyInt(), any());
        // The parent is read once, with the child-list projection, and reused for the
        // breadcrumb instead of being fetched a second time with full details.
        verify(openMetadataClient, times(1)).getAssetDetail(
                eq(ExplorationAssetType.DIRECTORY), eq("dir-1"), eq(false),
                eq("service,parent,children"));
    }

    @Test
    void rejectsAParentOwnedByAnotherService() {
        stubSource(DbType.SFTP, List.of(ExplorationAssetType.DIRECTORY, ExplorationAssetType.FILE), true);
        OpenMetadataAsset foreign = asset(
                "dir-1", "data", "other.data", "directory", null, null, "other",
                null, null, null, null);
        when(openMetadataClient.getAssetDetail(eq(ExplorationAssetType.DIRECTORY), eq("dir-1"), anyBoolean(), anyString()))
                .thenReturn(new OpenMetadataAssetDetail(
                        foreign, List.of(), List.of(), List.of(), null,
                        false, false, List.of(), Map.of()));

        assertThrows(RuntimeException.class,
                () -> service().listAssets(42L, "dir-1", "DIRECTORY", 1, 50, null));
        verify(openMetadataClient, never())
                .listFilesInDirectory(anyString(), anyInt(), any());
    }

    @Test
    void rejectsListingBeforeTheBindingIsReady() {
        when(dataSourceDao.queryById(42L)).thenReturn(source(DbType.KAFKA));
        when(connectorRegistry.find(DbType.KAFKA)).thenReturn(Optional.of(adapter));
        when(adapter.resourceTypes()).thenReturn(List.of(ExplorationAssetType.TOPIC));
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setDesiredState(MetadataDesiredState.ACTIVE);
        binding.setSyncStatus(MetadataSyncStatus.PENDING);
        when(metadataBindingDao.queryByDataSourceId(42L)).thenReturn(binding);

        assertThrows(RuntimeException.class,
                () -> service().listAssets(42L, null, null, 1, 50, null));
        verify(openMetadataClient, never()).listAssetsPage(any(), anyString(), any(), anyBoolean(), anyInt(), any());
    }

    @Test
    void reportsAvailableSampleAndHidesItWhenCollectionIsDisabled() {
        stubSource(DbType.KAFKA, List.of(ExplorationAssetType.TOPIC), true);
        OpenMetadataAsset topic = topic("topic-1", "orders.created", 1);
        when(openMetadataClient.getAssetDetail(eq(ExplorationAssetType.TOPIC), eq("topic-1"), anyBoolean(), anyString()))
                .thenReturn(new OpenMetadataAssetDetail(
                        topic,
                        List.of(new OpenMetadataAssetField("id", "INT", null, List.of())),
                        List.of(), List.of(),
                        OpenMetadataAssetSample.messages(List.of("{\"id\":1}")),
                        false, false, List.of(), Map.of("partitions", 3)));

        DataExplorationAssetDetailVO available =
                service().getAsset(42L, "topic-1", "TOPIC");
        assertEquals("AVAILABLE", available.getSample().getStatus());
        assertEquals(List.of("{\"id\":1}"), available.getSample().getMessages());
        assertEquals("MESSAGE", available.getSchema().getKind());
        assertEquals("AVAILABLE", available.getSchema().getStatus());
        assertEquals("分区数", available.getProperties().get(0).getLabel());

        stubSource(DbType.KAFKA, List.of(ExplorationAssetType.TOPIC), false);
        DataExplorationAssetDetailVO disabled =
                service().getAsset(42L, "topic-1", "TOPIC");
        assertEquals("DISABLED", disabled.getSample().getStatus());
        assertTrue(disabled.getSample().getMessages().isEmpty());
    }

    @Test
    void explainsMissingContainerDataModelBeforeSampleState() {
        stubSource(DbType.MINIO, List.of(ExplorationAssetType.CONTAINER), false);
        OpenMetadataAsset container = asset(
                "container-1", "bucket", "st_ds_42.bucket", "container", null, null,
                "st_ds_42", null, null, null, 1024L);
        when(openMetadataClient.getAssetDetail(eq(ExplorationAssetType.CONTAINER), eq("container-1"), anyBoolean(), anyString()))
                .thenReturn(new OpenMetadataAssetDetail(
                        container, List.of(), List.of(), List.of(), null,
                        false, false, List.of(), Map.of("dataModelAvailable", false)));

        DataExplorationAssetDetailVO detail =
                service().getAsset(42L, "container-1", "CONTAINER");

        assertEquals("NEEDS_DATA_MODEL", detail.getSample().getStatus());
        assertEquals("NEEDS_DATA_MODEL", detail.getSchema().getStatus());
    }

    @Test
    void marksNonTabularFilesAsNotApplicable() {
        stubSource(DbType.SFTP, List.of(ExplorationAssetType.DIRECTORY, ExplorationAssetType.FILE), true);
        OpenMetadataAsset file = asset(
                "file-1", "image.png", "st_ds_42.data.image.png", "file", 0, null,
                "st_ds_42", "dir-1", "directory", "st_ds_42.data", 4096L);
        when(openMetadataClient.getAssetDetail(eq(ExplorationAssetType.FILE), eq("file-1"), anyBoolean(), anyString()))
                .thenReturn(new OpenMetadataAssetDetail(
                        file, List.of(), List.of(), List.of(), null,
                        false, false, List.of(),
                        Map.of("fileType", "PNG", "mimeType", "image/png")));

        DataExplorationAssetDetailVO detail =
                service().getAsset(42L, "file-1", "FILE");

        assertEquals("NOT_APPLICABLE", detail.getSample().getStatus());
        assertEquals("NOT_APPLICABLE", detail.getSchema().getStatus());
    }

    @Test
    void reportsPermissionDeniedForSampleReads() {
        stubSource(DbType.KAFKA, List.of(ExplorationAssetType.TOPIC), true);
        when(openMetadataClient.getAssetDetail(eq(ExplorationAssetType.TOPIC), eq("topic-1"), anyBoolean(), anyString()))
                .thenReturn(new OpenMetadataAssetDetail(
                        topic("topic-1", "orders", 1),
                        List.of(new OpenMetadataAssetField("id", "INT", null, List.of())),
                        List.of(), List.of(), null, true, false, List.of(), Map.of()));

        DataExplorationAssetDetailVO detail =
                service().getAsset(42L, "topic-1", "TOPIC");

        assertEquals("PERMISSION_DENIED", detail.getSample().getStatus());
        assertEquals("AVAILABLE", detail.getSchema().getStatus());
    }

    @Test
    void treatsApiEndpointsAsRequestAndResponseSchemas() {
        stubSource(DbType.HTTP, List.of(ExplorationAssetType.API_COLLECTION, ExplorationAssetType.API_ENDPOINT), true);
        OpenMetadataAsset endpoint = asset(
                "endpoint-1", "GET /orders", "st_ds_42.orders.GET /orders", "apiEndpoint",
                2, null, "st_ds_42", "collection-1", "apiCollection",
                "st_ds_42.orders", null);
        when(openMetadataClient.getAssetDetail(eq(ExplorationAssetType.API_ENDPOINT), eq("endpoint-1"), anyBoolean(), anyString()))
                .thenReturn(new OpenMetadataAssetDetail(
                        endpoint,
                        List.of(),
                        List.of(new OpenMetadataAssetField("id", "STRING", null, List.of())),
                        List.of(), null, false, false, List.of(), Map.of()));

        DataExplorationAssetDetailVO detail =
                service().getAsset(42L, "endpoint-1", "API_ENDPOINT");

        assertEquals("REQUEST_RESPONSE", detail.getSchema().getKind());
        assertEquals("AVAILABLE", detail.getSchema().getStatus());
        assertEquals(1, detail.getSchema().getRequestFields().size());
        assertTrue(detail.getSchema().getResponseFields().isEmpty());
        assertEquals("NOT_SUPPORTED", detail.getSample().getStatus());
    }

    @Test
    void readsAncestorsWithTheLightweightParentProjection() {
        stubSource(DbType.HTTP,
                List.of(ExplorationAssetType.API_COLLECTION, ExplorationAssetType.API_ENDPOINT), true);
        OpenMetadataAsset endpoint = asset(
                "endpoint-1", "GET /orders", "st_ds_42.default.GET /orders", "apiEndpoint",
                0, null, "st_ds_42", "collection-1", "apiCollection",
                "st_ds_42.default", null);
        OpenMetadataAsset collection = asset(
                "collection-1", "default", "st_ds_42.default", "apiCollection",
                null, 0, "st_ds_42", null, null, null, null);
        when(openMetadataClient.getAssetDetail(
                eq(ExplorationAssetType.API_ENDPOINT), eq("endpoint-1"), eq(true), anyString()))
                .thenReturn(new OpenMetadataAssetDetail(
                        endpoint, List.of(), List.of(), List.of(), null,
                        false, false, List.of(), Map.of()));
        when(openMetadataClient.getAssetDetail(
                eq(ExplorationAssetType.API_COLLECTION), eq("collection-1"), eq(false),
                eq("service")))
                .thenReturn(new OpenMetadataAssetDetail(
                        collection, List.of(), List.of(), List.of(), null,
                        false, false, List.of(), Map.of()));

        DataExplorationAssetDetailVO detail =
                service().getAsset(42L, "endpoint-1", "API_ENDPOINT");

        assertEquals(1, detail.getBreadcrumbs().size());
        assertEquals("collection-1", detail.getBreadcrumbs().get(0).getId());
        verify(openMetadataClient).getAssetDetail(
                eq(ExplorationAssetType.API_COLLECTION), eq("collection-1"), eq(false),
                eq("service"));
    }

    @Test
    void searchesApiEndpointsByMethodAndCarriesCollectionDescription() {
        stubSource(DbType.HTTP,
                List.of(ExplorationAssetType.API_COLLECTION, ExplorationAssetType.API_ENDPOINT), true);
        OpenMetadataAsset collection = new OpenMetadataAsset(
                "collection-1", "orders", "orders", "st_ds_42.orders", "apiCollection",
                "Order operations", null, null, List.of(), "st_ds_42", null, null, null,
                null, null, null, null);
        OpenMetadataAsset endpoint = new OpenMetadataAsset(
                "endpoint-1", "/orders", "/orders", "st_ds_42.orders.POST /orders",
                "apiEndpoint", "Create an order", 1, null, List.of(), "st_ds_42",
                "collection-1", "apiCollection", "st_ds_42.orders", null, null,
                "POST", "/orders");
        when(openMetadataClient.getAssetDetail(
                eq(ExplorationAssetType.API_COLLECTION), eq("collection-1"), eq(false), eq("service")))
                .thenReturn(new OpenMetadataAssetDetail(
                        collection, List.of(), List.of(), List.of(), null,
                        false, false, List.of(), Map.of()));
        when(openMetadataClient.listAssetsPage(
                eq(ExplorationAssetType.API_ENDPOINT), eq("st_ds_42"),
                eq("st_ds_42.orders"), eq(false), anyInt(), any()))
                .thenReturn(new OpenMetadataPage<>(List.of(endpoint), 1L, null));

        DataExplorationAssetPageVO page = service().listAssets(
                42L, "collection-1", "API_COLLECTION", 1, 50, "post");

        assertEquals(1, page.getRecords().size());
        assertEquals("POST", page.getRecords().get(0).getRequestMethod());
        assertEquals("/orders", page.getRecords().get(0).getEndpointURL());
        assertEquals("Order operations", page.getBreadcrumbs().get(0).getDescription());
    }

    @Test
    void continuesApiCollectionScanAfterPagesWithoutMatchingEndpoints() {
        stubSource(DbType.HTTP,
                List.of(ExplorationAssetType.API_COLLECTION, ExplorationAssetType.API_ENDPOINT), true);
        OpenMetadataAsset collection = new OpenMetadataAsset(
                "collection-1", "orders", "orders", "st_ds_42.orders", "apiCollection",
                "Order operations", null, null, List.of(), "st_ds_42", null, null, null,
                null, null, null, null);
        OpenMetadataAsset endpoint = new OpenMetadataAsset(
                "endpoint-1", "/orders", "/orders", "st_ds_42.orders.POST /orders",
                "apiEndpoint", "Create an order", 1, null, List.of(), "st_ds_42",
                "collection-1", "apiCollection", "st_ds_42.orders", null, null,
                "POST", "/orders");
        when(openMetadataClient.getAssetDetail(
                eq(ExplorationAssetType.API_COLLECTION), eq("collection-1"), eq(false), eq("service")))
                .thenReturn(new OpenMetadataAssetDetail(
                        collection, List.of(), List.of(), List.of(), null,
                        false, false, List.of(), Map.of()));
        when(openMetadataClient.listAssetsPage(
                eq(ExplorationAssetType.API_ENDPOINT), eq("st_ds_42"),
                eq("st_ds_42.orders"), eq(false), anyInt(), any()))
                .thenReturn(new OpenMetadataPage<>(List.of(), 3L, "cursor-1", 1))
                .thenReturn(new OpenMetadataPage<>(List.of(), 3L, "cursor-2", 1))
                .thenReturn(new OpenMetadataPage<>(List.of(endpoint), 3L, null, 1));

        DataExplorationAssetPageVO page = service().listAssets(
                42L, "collection-1", "API_COLLECTION", 1, 50, null);

        assertEquals(1, page.getRecords().size());
        assertEquals("POST", page.getRecords().get(0).getRequestMethod());
        verify(openMetadataClient).listAssetsPage(
                eq(ExplorationAssetType.API_ENDPOINT), eq("st_ds_42"),
                eq("st_ds_42.orders"), eq(false), anyInt(), eq(null));
        verify(openMetadataClient).listAssetsPage(
                eq(ExplorationAssetType.API_ENDPOINT), eq("st_ds_42"),
                eq("st_ds_42.orders"), eq(false), anyInt(), eq("cursor-1"));
        verify(openMetadataClient).listAssetsPage(
                eq(ExplorationAssetType.API_ENDPOINT), eq("st_ds_42"),
                eq("st_ds_42.orders"), eq(false), anyInt(), eq("cursor-2"));
    }

    @Test
    void listsContainerChildrenWithOffsetPagination() {
        stubSource(DbType.MINIO, List.of(ExplorationAssetType.CONTAINER), true);
        OpenMetadataAsset parent = asset(
                "container-1", "bucket", "st_ds_42.bucket", "container", null, null,
                "st_ds_42", null, null, null, null);
        when(openMetadataClient.getAssetDetail(eq(ExplorationAssetType.CONTAINER), eq("container-1"), anyBoolean(), anyString()))
                .thenReturn(new OpenMetadataAssetDetail(
                        parent, List.of(), List.of(), List.of(), null,
                        false, false, List.of(), Map.of("dataModelAvailable", true)));
        when(openMetadataClient.listContainerChildren(eq("st_ds_42.bucket"), anyInt(), eq(0)))
                .thenReturn(new OpenMetadataPage<>(
                        List.of(asset("container-2", "raw", "st_ds_42.bucket.raw", "container",
                                null, null, "st_ds_42", "container-1", "container",
                                "st_ds_42.bucket", null)), 1L, null));

        DataExplorationAssetPageVO page =
                service().listAssets(42L, "container-1", "CONTAINER", 1, 50, null);

        assertEquals(1, page.getRecords().size());
        assertEquals("raw", page.getRecords().get(0).getName());
        verify(openMetadataClient).listContainerChildren(eq("st_ds_42.bucket"), anyInt(), eq(0));
    }

    @Test
    void listsDriveRootsAsDirectoriesAndFiles() {
        stubSource(DbType.SFTP, List.of(ExplorationAssetType.DIRECTORY, ExplorationAssetType.FILE), true);
        when(openMetadataClient.listAssetsPage(
                eq(ExplorationAssetType.DIRECTORY), eq("st_ds_42"), isNull(), eq(true), anyInt(), any()))
                .thenReturn(new OpenMetadataPage<>(
                        List.of(asset("dir-1", "data", "st_ds_42.data", "directory",
                                null, null, "st_ds_42", null, null, null, null)), 1L, null));
        when(openMetadataClient.listAssetsPage(
                eq(ExplorationAssetType.FILE), eq("st_ds_42"), isNull(), eq(true), anyInt(), any()))
                .thenReturn(new OpenMetadataPage<>(
                        List.of(asset("file-1", "root.csv", "st_ds_42.root.csv", "file",
                                3, null, "st_ds_42", null, null, null, 10L)), 1L, null));

        DataExplorationAssetPageVO page =
                service().listAssets(42L, null, null, 1, 50, null);

        assertEquals(2, page.getRecords().size());
        assertEquals("DIRECTORY", page.getRecords().get(0).getAssetType());
        assertEquals("FILE", page.getRecords().get(1).getAssetType());
    }

    @Test
    void filtersBySearchAndPaginatesTheLevel() {
        stubSource(DbType.KAFKA, List.of(ExplorationAssetType.TOPIC), true);
        when(openMetadataClient.listAssetsPage(
                eq(ExplorationAssetType.TOPIC), eq("st_ds_42"), isNull(), eq(false), anyInt(), any()))
                .thenReturn(new OpenMetadataPage<>(List.of(
                        topic("topic-1", "orders.created", 1),
                        topic("topic-2", "orders.paid", 1),
                        topic("topic-3", "users", 1)), 3L, null));

        DataExplorationAssetPageVO page =
                service().listAssets(42L, null, null, 2, 1, "orders");

        assertEquals(1, page.getRecords().size());
        assertEquals("orders.paid", page.getRecords().get(0).getName());
        assertEquals(2, page.getTotal());
        assertEquals(2, page.getPageNo());
    }

    @Test
    void rejectsAnAssetTypeTheAdapterDoesNotExpose() {
        stubSource(DbType.KAFKA, List.of(ExplorationAssetType.TOPIC), true);

        assertThrows(RuntimeException.class,
                () -> service().getAsset(42L, "container-1", "CONTAINER"));
        verify(openMetadataClient, never())
                .getAssetDetail(eq(ExplorationAssetType.CONTAINER), anyString(), anyBoolean(), anyString());
    }


    @Test
    void hidesNestedContainersFromTheRootLevel() {
        stubSource(DbType.MINIO, List.of(ExplorationAssetType.CONTAINER), true);
        OpenMetadataAsset root = asset(
                "container-1", "bucket", "st_ds_42.bucket", "container", null, null,
                "st_ds_42", null, null, null, 0L);
        OpenMetadataAsset nested = asset(
                "container-2", "prefix", "st_ds_42.bucket.prefix", "container", null, null,
                "st_ds_42", "container-1", "container", "st_ds_42.bucket", null);
        when(openMetadataClient.listAssetsPage(
                eq(ExplorationAssetType.CONTAINER), eq("st_ds_42"), isNull(), eq(false), anyInt(), any()))
                .thenReturn(new OpenMetadataPage<>(List.of(root, nested), 2L, null));

        DataExplorationAssetPageVO page =
                service().listAssets(42L, null, null, 1, 50, null);

        assertEquals(1, page.getRecords().size());
        assertEquals("bucket", page.getRecords().get(0).getName());
    }


    @Test
    void reportsNotCollectedAndSampleErrors() {
        stubSource(DbType.KAFKA, List.of(ExplorationAssetType.TOPIC), true);
        OpenMetadataAsset topic = topic("topic-1", "orders", 1);
        when(openMetadataClient.getAssetDetail(eq(ExplorationAssetType.TOPIC), eq("topic-1"), anyBoolean(), anyString()))
                .thenReturn(new OpenMetadataAssetDetail(
                        topic,
                        List.of(new OpenMetadataAssetField("id", "INT", null, List.of())),
                        List.of(), List.of(), null, false, false, List.of(), Map.of()));
        assertEquals("NOT_COLLECTED",
                service().getAsset(42L, "topic-1", "TOPIC").getSample().getStatus());

        when(openMetadataClient.getAssetDetail(eq(ExplorationAssetType.TOPIC), eq("topic-1"), anyBoolean(), anyString()))
                .thenReturn(new OpenMetadataAssetDetail(
                        topic,
                        List.of(new OpenMetadataAssetField("id", "INT", null, List.of())),
                        List.of(), List.of(), null, false, true, List.of(), Map.of()));
        assertEquals("ERROR",
                service().getAsset(42L, "topic-1", "TOPIC").getSample().getStatus());
    }

    @Test
    void reportsNoSchemaForAnEmptyTopic() {
        stubSource(DbType.KAFKA, List.of(ExplorationAssetType.TOPIC), true);
        when(openMetadataClient.getAssetDetail(eq(ExplorationAssetType.TOPIC), eq("topic-1"), anyBoolean(), anyString()))
                .thenReturn(new OpenMetadataAssetDetail(
                        topic("topic-1", "orders", 0),
                        List.of(), List.of(), List.of(), null,
                        false, false, List.of(), Map.of()));

        DataExplorationAssetDetailVO detail =
                service().getAsset(42L, "topic-1", "TOPIC");

        assertEquals("NO_SCHEMA", detail.getSchema().getStatus());
    }

    @Test
    void directoryAndApiCollectionDoNotPretendToHaveSchemaOrSample() {
        stubSource(DbType.SFTP, List.of(ExplorationAssetType.DIRECTORY, ExplorationAssetType.FILE), true);
        OpenMetadataAsset directory = asset(
                "dir-1", "data", "st_ds_42.data", "directory", null, 2,
                "st_ds_42", null, null, null, 1024L);
        when(openMetadataClient.getAssetDetail(eq(ExplorationAssetType.DIRECTORY), eq("dir-1"), anyBoolean(), anyString()))
                .thenReturn(new OpenMetadataAssetDetail(
                        directory, List.of(), List.of(), List.of(), null,
                        false, false, List.of(), Map.of()));

        DataExplorationAssetDetailVO detail =
                service().getAsset(42L, "dir-1", "DIRECTORY");

        assertEquals("NOT_SUPPORTED", detail.getSchema().getStatus());
        assertEquals("NOT_SUPPORTED", detail.getSample().getStatus());
    }

    @Test
    void rejectsAssetDetailsOwnedByAnotherService() {
        stubSource(DbType.KAFKA, List.of(ExplorationAssetType.TOPIC), true);
        when(openMetadataClient.getAssetDetail(eq(ExplorationAssetType.TOPIC), eq("topic-1"), anyBoolean(), anyString()))
                .thenReturn(new OpenMetadataAssetDetail(
                        asset("topic-1", "orders", "other.orders", "topic", 1, null,
                                "other", null, null, null, null),
                        List.of(), List.of(), List.of(), null,
                        false, false, List.of(), Map.of()));

        assertThrows(RuntimeException.class,
                () -> service().getAsset(42L, "topic-1", "TOPIC"));
    }

    @Test
    void marksTheLevelTruncatedWhenTheScanCapIsReached() {
        stubSource(DbType.KAFKA, List.of(ExplorationAssetType.TOPIC), true);
        List<OpenMetadataAsset> page = new ArrayList<>();
        for (int index = 0; index < 1000; index++) {
            page.add(topic("topic-" + index, "topic-" + index, 0));
        }
        when(openMetadataClient.listAssetsPage(
                eq(ExplorationAssetType.TOPIC), eq("st_ds_42"), isNull(), eq(false), anyInt(), any()))
                .thenReturn(new OpenMetadataPage<>(page, 10_000L, "next"));

        DataExplorationAssetPageVO result =
                service().listAssets(42L, null, null, 1, 50, "does-not-match");

        assertTrue(result.isTruncated());
        assertEquals(0, result.getTotal());
    }

    @Test
    void boundsCursorScanningWhenEveryPageIsFilteredOut() {
        stubSource(DbType.KAFKA, List.of(ExplorationAssetType.TOPIC), true);
        when(openMetadataClient.listAssetsPage(
                eq(ExplorationAssetType.TOPIC), eq("st_ds_42"), isNull(), eq(false), anyInt(), any()))
                .thenReturn(new OpenMetadataPage<>(List.of(), 10_000L, "next", 1000));

        DataExplorationAssetPageVO result =
                service().listAssets(42L, null, null, 1, 50, "does-not-match");

        assertTrue(result.isTruncated());
        assertEquals(0, result.getTotal());
        verify(openMetadataClient, times(5)).listAssetsPage(
                eq(ExplorationAssetType.TOPIC), eq("st_ds_42"), isNull(), eq(false), anyInt(), any());
    }

    private void stubSource(
            DbType dbType, List<ExplorationAssetType> types, boolean sampleDataEnabled) {
        when(dataSourceDao.queryById(42L)).thenReturn(source(dbType));
        when(connectorRegistry.find(dbType)).thenReturn(Optional.of(adapter));
        when(adapter.resourceTypes()).thenReturn(types);
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setDataSourceId(42L);
        binding.setDesiredState(MetadataDesiredState.ACTIVE);
        binding.setSyncStatus(MetadataSyncStatus.READY);
        binding.setOmServiceFqn("st_ds_42");
        binding.setSampleDataEnabled(sampleDataEnabled);
        when(metadataBindingDao.queryByDataSourceId(42L)).thenReturn(binding);
    }

    private static DataSource source(DbType dbType) {
        DataSource source = new DataSource();
        source.setId(42L);
        source.setName("events");
        source.setDbType(dbType);
        source.setStatus(DataSourceLifecycleStatus.ENABLED);
        return source;
    }

    private static OpenMetadataAsset topic(String id, String name, int fieldCount) {
        return asset(id, name, "st_ds_42." + name, "topic", fieldCount, null,
                "st_ds_42", null, null, null, null);
    }

    private static OpenMetadataAsset asset(
            String id,
            String name,
            String fqn,
            String entityType,
            Integer fieldCount,
            Integer childCount,
            String serviceFqn,
            String parentId,
            String parentType,
            String parentFqn,
            Long sizeInByte) {
        return new OpenMetadataAsset(
                id, name, name, fqn, entityType, null, fieldCount, childCount,
                List.of(), serviceFqn, parentId, parentType, parentFqn, sizeInByte, null, null, null);
    }

    private AssetExplorationService service() {
        OpenMetadataProperties properties = new OpenMetadataProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:18585/api");
        properties.setToken("test-token");
        return new AssetExplorationService(
                OpenMetadataConfigResolver.fixed(properties),
                dataSourceDao,
                metadataBindingDao,
                connectorRegistry,
                openMetadataClient);
    }
}
