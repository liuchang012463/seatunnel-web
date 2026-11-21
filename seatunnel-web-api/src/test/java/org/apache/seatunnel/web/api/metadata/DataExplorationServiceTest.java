package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataColumn;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataColumnProfile;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabase;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabaseSchema;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTable;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTableConstraint;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTableProfile;
import org.apache.seatunnel.web.common.QueryResult;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.bean.dto.DataExplorationMetadataUpdateDTO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationColumnProfileVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationErDiagramVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationProfileVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationTableDetailVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationTablePageVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationTableVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataExplorationServiceTest {

    @Mock private DataSourceDao dataSourceDao;
    @Mock private MetadataBindingDao metadataBindingDao;
    @Mock private OpenMetadataClient openMetadataClient;
    @Mock private org.apache.seatunnel.web.api.service.DataSourceCatalogService catalogService;

    @Test
    void listsOnlyDatabasesOwnedByTheDataSourceBinding() {
        stubReadySource();
        doReturn(new OpenMetadataPage<>(List.of(
                new OpenMetadataDatabase("db-id", "st_ds_42.orders", "st_ds_42"),
                new OpenMetadataDatabase("foreign-id", "other.orders", "other")), 2L, null))
                .when(openMetadataClient).listDatabasesPage("st_ds_42", 1000, null);

        assertEquals(1, service().listDatabases(42L).size());
        assertEquals("st_ds_42.orders", service().listDatabases(42L).get(0).getFullyQualifiedName());
    }

    @Test
    void separatesExplorationPageAndProfileCachesByDomainScope() {
        stubReadySource();
        String databaseFqn = "st_ds_42.orders";
        String schemaFqn = databaseFqn + ".public";
        AtomicReference<String> scope = new AtomicReference<>("domain-a");
        AtomicInteger databaseLoads = new AtomicInteger();
        AtomicInteger schemaLoads = new AtomicInteger();
        AtomicInteger tableLoads = new AtomicInteger();
        AtomicInteger databaseTableLoads = new AtomicInteger();
        AtomicInteger profileLoads = new AtomicInteger();
        when(openMetadataClient.cacheScopeKey()).thenAnswer(invocation -> scope.get());
        when(openMetadataClient.findDatabase(databaseFqn))
                .thenReturn(Optional.of(new OpenMetadataDatabase("db-id", databaseFqn, "st_ds_42")));

        doAnswer(invocation -> {
            databaseLoads.incrementAndGet();
            String label = scopeLabel(scope.get());
            return new OpenMetadataPage<>(List.of(new OpenMetadataDatabase(
                    "db-" + label, "st_ds_42." + label, "st_ds_42")), 1L, null);
        }).when(openMetadataClient).listDatabasesPage("st_ds_42", 1000, null);
        doAnswer(invocation -> {
            schemaLoads.incrementAndGet();
            return new OpenMetadataPage<>(List.of(schema(
                    "schema-" + scopeLabel(scope.get()), schemaFqn)), 1L, null);
        }).when(openMetadataClient).listSchemasPage(databaseFqn, 1000, null);
        doAnswer(invocation -> {
            tableLoads.incrementAndGet();
            OpenMetadataTable table = table("table-" + scopeLabel(scope.get()), "orders");
            OpenMetadataTableProfile profile = new OpenMetadataTableProfile();
            profile.setTimestamp(1700000000000L);
            table.setProfile(profile);
            return new OpenMetadataPage<>(List.of(table), 1L, null);
        }).when(openMetadataClient).listTablesPage(schemaFqn, true, 20, null);
        doAnswer(invocation -> {
            databaseTableLoads.incrementAndGet();
            return new OpenMetadataPage<>(List.of(table(
                    "diagram-" + scopeLabel(scope.get()), "orders")), 1L, null);
        }).when(openMetadataClient).listTablesByDatabasePage(databaseFqn, true, 1000, null);
        doAnswer(invocation -> {
            profileLoads.incrementAndGet();
            OpenMetadataTableProfile profile = new OpenMetadataTableProfile();
            long metric = scopeMetric(scope.get());
            profile.setTimestamp(1700000000000L + metric);
            profile.setRowCount(metric);
            return profile;
        }).when(openMetadataClient).getLatestTableProfile("st_ds_42.orders.public.orders");
        when(openMetadataClient.getTable("profile-table")).thenReturn(table("profile-table", "orders"));

        DataExplorationService service = service(redisCache(new ConcurrentHashMap<>()));

        assertEquals("db-a", service.listDatabases(42L).get(0).getId());
        scope.set("domain-b");
        assertEquals("db-b", service.listDatabases(42L).get(0).getId());
        scope.set("");
        assertEquals("db-all", service.listDatabases(42L).get(0).getId());
        scope.set("domain-a");
        assertEquals("db-a", service.listDatabases(42L).get(0).getId());
        assertEquals(3, databaseLoads.get());

        assertEquals("schema-a", service.listSchemas(42L, databaseFqn).get(0).getId());
        scope.set("domain-b");
        assertEquals("schema-b", service.listSchemas(42L, databaseFqn).get(0).getId());
        scope.set("");
        assertEquals("schema-all", service.listSchemas(42L, databaseFqn).get(0).getId());
        scope.set("domain-a");
        assertEquals("schema-a", service.listSchemas(42L, databaseFqn).get(0).getId());
        assertEquals(3, schemaLoads.get());

        assertEquals("table-a", service.listTables(42L, databaseFqn, schemaFqn, 1, 20)
                .getRecords().get(0).getId());
        scope.set("domain-b");
        assertEquals("table-b", service.listTables(42L, databaseFqn, schemaFqn, 1, 20)
                .getRecords().get(0).getId());
        scope.set("");
        assertEquals("table-all", service.listTables(42L, databaseFqn, schemaFqn, 1, 20)
                .getRecords().get(0).getId());
        scope.set("domain-a");
        assertEquals("table-a", service.listTables(42L, databaseFqn, schemaFqn, 1, 20)
                .getRecords().get(0).getId());
        assertEquals(3, tableLoads.get());

        assertEquals("diagram-a", service.getErDiagram(42L, databaseFqn, null).getNodes().get(0).getId());
        scope.set("domain-b");
        assertEquals("diagram-b", service.getErDiagram(42L, databaseFqn, null).getNodes().get(0).getId());
        scope.set("");
        assertEquals("diagram-all", service.getErDiagram(42L, databaseFqn, null).getNodes().get(0).getId());
        scope.set("domain-a");
        assertEquals("diagram-a", service.getErDiagram(42L, databaseFqn, null).getNodes().get(0).getId());
        assertEquals(3, databaseTableLoads.get());

        assertEquals(10L, service.getProfile(42L, "profile-table").getTable().getRowCount());
        scope.set("domain-b");
        assertEquals(20L, service.getProfile(42L, "profile-table").getTable().getRowCount());
        scope.set("");
        assertEquals(30L, service.getProfile(42L, "profile-table").getTable().getRowCount());
        scope.set("domain-a");
        assertEquals(10L, service.getProfile(42L, "profile-table").getTable().getRowCount());
        assertEquals(3, profileLoads.get());

        assertEquals(1700000000010L, service.getTable(42L, "profile-table").getProfileTime());
        scope.set("domain-b");
        assertEquals(1700000000020L, service.getTable(42L, "profile-table").getProfileTime());
        scope.set("");
        assertEquals(1700000000030L, service.getTable(42L, "profile-table").getProfileTime());
        scope.set("domain-a");
        assertEquals(1700000000010L, service.getTable(42L, "profile-table").getProfileTime());
        assertEquals(3, profileLoads.get());
    }

    @Test
    void mapsTableListAndDetailWithoutPersistingOpenMetadataRowsLocally() {
        stubReadySource();
        OpenMetadataColumn column = new OpenMetadataColumn();
        column.setName("id");
        column.setDataType("INT");
        column.setConstraint("PRIMARY_KEY");
        OpenMetadataTableConstraint constraint = new OpenMetadataTableConstraint();
        constraint.setConstraintType("PRIMARY_KEY");
        constraint.setColumns(List.of("id"));
        OpenMetadataTable table = table("table-id", "orders");
        table.setColumns(List.of(column));
        table.setTableConstraints(List.of(constraint));
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("db-id", "st_ds_42.orders", "st_ds_42")));
        doReturn(new OpenMetadataPage<>(List.of(schema("schema-id", "st_ds_42.orders.public")), 1L, null))
                .when(openMetadataClient).listSchemasPage("st_ds_42.orders", 1000, null);
        doReturn(new OpenMetadataPage<>(List.of(table), 1L, null))
                .when(openMetadataClient).listTablesPage("st_ds_42.orders.public", true, 20, null);
        when(openMetadataClient.getTable("table-id")).thenReturn(table);

        DataExplorationTablePageVO page = service().listTables(
                42L, "st_ds_42.orders", "st_ds_42.orders.public", 1, 20);
        DataExplorationTableDetailVO detail = service().getTable(42L, "table-id");

        assertEquals(1, page.getRecords().size());
        DataExplorationTableVO item = page.getRecords().get(0);
        assertEquals(1, item.getColumnCount());
        assertEquals("table-id", detail.getId());
        assertEquals("PRIMARY_KEY", detail.getColumns().get(0).getConstraint());
        assertFalse(detail.isProfileAvailable());
        assertNull(detail.getProfileTime());
    }

    @Test
    void mapsProfileAvailabilityFromTheLatestTimeSeriesProfile() {
        stubReadySource();
        OpenMetadataTable table = table("table-id", "orders");
        String databaseFqn = "st_ds_42.orders";
        String schemaFqn = "st_ds_42.orders.public";
        when(openMetadataClient.findDatabase(databaseFqn))
                .thenReturn(Optional.of(new OpenMetadataDatabase("db-id", databaseFqn, "st_ds_42")));
        doReturn(new OpenMetadataPage<>(List.of(schema("schema-id", schemaFqn)), 1L, null))
                .when(openMetadataClient).listSchemasPage(databaseFqn, 1000, null);
        doReturn(new OpenMetadataPage<>(List.of(table), 1L, null))
                .when(openMetadataClient).listTablesPage(schemaFqn, true, 20, null);
        OpenMetadataTableProfile profile = new OpenMetadataTableProfile();
        profile.setTimestamp(1700000000000L);
        when(openMetadataClient.getLatestTableProfile(table.getFullyQualifiedName())).thenReturn(profile);

        DataExplorationTableVO row = service()
                .listTables(42L, databaseFqn, schemaFqn, 1, 20)
                .getRecords()
                .get(0);

        assertTrue(row.isProfileAvailable());
        assertEquals(1700000000000L, row.getProfileTime());
        verify(openMetadataClient).getLatestTableProfile(table.getFullyQualifiedName());
    }

    @Test
    void mapsLatestProfileAvailabilityIntoTableDetail() {
        stubReadySource();
        OpenMetadataTable table = table("table-id", "orders");
        when(openMetadataClient.getTable("table-id")).thenReturn(table);
        OpenMetadataTableProfile profile = new OpenMetadataTableProfile();
        profile.setTimestamp(1700000000000L);
        when(openMetadataClient.getLatestTableProfile(table.getFullyQualifiedName())).thenReturn(profile);

        DataExplorationTableDetailVO detail = service().getTable(42L, "table-id");

        assertTrue(detail.isProfileAvailable());
        assertEquals(1700000000000L, detail.getProfileTime());
    }

    @Test
    void keepsTableListAvailableWhenLatestProfileLookupFails() {
        stubReadySource();
        OpenMetadataTable table = table("table-id", "orders");
        String databaseFqn = "st_ds_42.orders";
        String schemaFqn = "st_ds_42.orders.public";
        when(openMetadataClient.findDatabase(databaseFqn))
                .thenReturn(Optional.of(new OpenMetadataDatabase("db-id", databaseFqn, "st_ds_42")));
        doReturn(new OpenMetadataPage<>(List.of(schema("schema-id", schemaFqn)), 1L, null))
                .when(openMetadataClient).listSchemasPage(databaseFqn, 1000, null);
        doReturn(new OpenMetadataPage<>(List.of(table), 1L, null))
                .when(openMetadataClient).listTablesPage(schemaFqn, true, 20, null);
        when(openMetadataClient.getLatestTableProfile(table.getFullyQualifiedName()))
                .thenThrow(new IllegalStateException("OpenMetadata unavailable"));

        DataExplorationTableVO row = service()
                .listTables(42L, databaseFqn, schemaFqn, 1, 20)
                .getRecords()
                .get(0);

        assertFalse(row.isProfileAvailable());
        assertNull(row.getProfileTime());
    }

    @Test
    void mapsLatestProfileMetricsAndDelegatesPreviewAfterOwnershipCheck() {
        stubReadySource();
        OpenMetadataTable table = table("table-id", "orders");
        table.setDatabaseFullyQualifiedName("st_ds_42.orders");
        table.setSchemaFullyQualifiedName("st_ds_42.orders.public");
        table.setServiceFullyQualifiedName("st_ds_42");
        OpenMetadataColumn id = new OpenMetadataColumn();
        id.setName("id");
        id.setDataType("INT");
        id.setConstraint("PRIMARY_KEY");
        table.setColumns(List.of(id));
        OpenMetadataTableProfile omProfile = new OpenMetadataTableProfile();
        omProfile.setTimestamp(1700000000000L);
        omProfile.setRowCount(100L);
        omProfile.setColumnCount(1L);
        OpenMetadataColumnProfile idProfile = new OpenMetadataColumnProfile();
        idProfile.setName("id");
        idProfile.setTimestamp(1700000000000L);
        idProfile.setValuesCount(100L);
        idProfile.setValidCount(100L);
        idProfile.setNullCount(0L);
        idProfile.setDistinctCount(100L);
        idProfile.setUniqueCount(100L);
        omProfile.setColumns(List.of(idProfile));
        when(openMetadataClient.getTable("table-id")).thenReturn(table);
        when(openMetadataClient.getLatestTableProfile("st_ds_42.orders.public.orders"))
                .thenReturn(omProfile);
        QueryResult expected = new QueryResult();
        expected.setTotal(100);
        when(catalogService.getTop20Data(eq(42L), any())).thenReturn(expected);

        DataExplorationProfileVO profile = service().getProfile(42L, "table-id");
        QueryResult preview = service().preview(42L, "table-id", Map.of());

        assertEquals(100L, profile.getTable().getRowCount());
        DataExplorationColumnProfileVO idResult = profile.getColumns().get(0);
        assertEquals("NORMAL", idResult.getQualityStatus().name());
        assertEquals(expected, preview);
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(catalogService).getTop20Data(eq(42L), request.capture());
        assertEquals("orders", request.getValue().get("table_path"));
    }

    @Test
    void preservesDottedOpenMetadataTableNameWhenDelegatingPreview() {
        stubReadySource();
        OpenMetadataTable table = tableWithFqn(
                "table-id", "Orders.Archive", "st_ds_42.orders.public.Orders.Archive");
        table.setDatabaseFullyQualifiedName("st_ds_42.orders");
        table.setSchemaFullyQualifiedName("st_ds_42.orders.public");
        when(openMetadataClient.getTable("table-id")).thenReturn(table);
        when(catalogService.getTop20Data(eq(42L), any())).thenReturn(new QueryResult());

        service().preview(42L, "table-id", Map.of());

        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(catalogService).getTop20Data(eq(42L), request.capture());
        assertEquals("\"Orders.Archive\"", request.getValue().get("table_path"));
        assertEquals("public", request.getValue().get("schema_name"));
    }

    @Test
    void hidesNegativeTableMetricsAsUnknown() {
        stubReadySource();
        OpenMetadataTable table = table("table-id", "orders");
        table.setServiceFullyQualifiedName("st_ds_42");
        OpenMetadataTableProfile omProfile = new OpenMetadataTableProfile();
        omProfile.setTimestamp(1700000000000L);
        omProfile.setRowCount(-1L);
        omProfile.setColumnCount(7L);
        omProfile.setSizeInByte(-1L);
        when(openMetadataClient.getTable("table-id")).thenReturn(table);
        when(openMetadataClient.getLatestTableProfile(table.getFullyQualifiedName())).thenReturn(omProfile);

        DataExplorationProfileVO profile = service().getProfile(42L, "table-id");

        assertNull(profile.getTable().getRowCount());
        assertEquals(7L, profile.getTable().getColumnCount());
        assertNull(profile.getTable().getSizeInByte());
    }

    @Test
    void buildsErDiagramNodesAndForeignKeyEdgesFromTableConstraints() {
        stubReadySource();
        String databaseFqn = "st_ds_42.orders";
        String schemaFqn = "st_ds_42.orders.public";
        when(openMetadataClient.findDatabase(databaseFqn))
                .thenReturn(Optional.of(new OpenMetadataDatabase("db-id", databaseFqn, "st_ds_42")));
        doReturn(new OpenMetadataPage<>(List.of(schema("schema-id", schemaFqn)), 1L, null))
                .when(openMetadataClient).listSchemasPage(databaseFqn, 1000, null);

        OpenMetadataTable customers = tableWithFqn(
                "customers-id", "customers", "st_ds_42.orders.public.customers");
        OpenMetadataColumn customerId = column("id", "BIGINT", "PRIMARY_KEY");
        customers.setColumns(List.of(customerId));
        OpenMetadataTableConstraint customerPrimaryKey = new OpenMetadataTableConstraint();
        customerPrimaryKey.setConstraintType("PRIMARY_KEY");
        customerPrimaryKey.setColumns(List.of("id"));
        customers.setTableConstraints(List.of(customerPrimaryKey));

        OpenMetadataTable orders = tableWithFqn(
                "orders-id", "orders", "st_ds_42.orders.public.orders");
        orders.setColumns(List.of(column("customer_id", "BIGINT", null)));
        OpenMetadataTableConstraint foreignKey = new OpenMetadataTableConstraint();
        foreignKey.setConstraintType("foreignKey");
        foreignKey.setColumns(List.of("customer_id"));
        foreignKey.setReferredColumns(List.of("st_ds_42.orders.public.customers.id"));
        orders.setTableConstraints(List.of(foreignKey));

        doReturn(new OpenMetadataPage<>(List.of(customers, orders), 2L, null))
                .when(openMetadataClient).listTablesPage(schemaFqn, true, 1000, null);

        DataExplorationErDiagramVO result = service().getErDiagram(42L, databaseFqn, schemaFqn);

        assertEquals(databaseFqn, result.getDatabaseFqn());
        assertEquals(2, result.getNodes().size());
        assertEquals(1, result.getEdges().size());
        assertEquals("orders-id", result.getEdges().get(0).getSource().getNodeId());
        assertEquals("customers-id", result.getEdges().get(0).getTarget().getNodeId());
        assertEquals(List.of("customer_id"), result.getEdges().get(0).getSource().getColumns());
        assertEquals(List.of("id"), result.getEdges().get(0).getTarget().getColumns());
    }

    @Test
    void usesDatabaseTableCollectionWhenSchemaIsOmitted() {
        stubReadySource();
        String databaseFqn = "st_ds_42.orders";
        OpenMetadataTable table = tableWithFqn(
                "orders-id", "orders", "st_ds_42.orders.public.orders");
        table.setDatabaseFullyQualifiedName(databaseFqn);
        when(openMetadataClient.findDatabase(databaseFqn))
                .thenReturn(Optional.of(new OpenMetadataDatabase("db-id", databaseFqn, "st_ds_42")));
        doReturn(new OpenMetadataPage<>(List.of(table), 1L, null))
                .when(openMetadataClient).listTablesByDatabasePage(databaseFqn, true, 1000, null);

        DataExplorationErDiagramVO result = service().getErDiagram(42L, databaseFqn, null);

        assertEquals(1, result.getNodes().size());
        assertEquals("orders-id", result.getNodes().get(0).getId());
        verify(openMetadataClient).listTablesByDatabasePage(databaseFqn, true, 1000, null);
    }

    @Test
    void updatesGovernanceFieldsWithReferencePatchSemantics() {
        stubReadySource();
        OpenMetadataTable table = table("table-id", "orders");
        when(openMetadataClient.getTable("table-id")).thenReturn(table);
        when(openMetadataClient.patchTable(eq("table-id"), any(JsonNode.class))).thenReturn(table);

        DataExplorationMetadataUpdateDTO request = new DataExplorationMetadataUpdateDTO();
        request.setDisplayName("订单");
        request.setDescription("订单主表");
        request.setRetentionPeriod("");
        request.setTags(List.of("PII.Sensitive"));
        request.setDomainId("");

        service().updateMetadata(42L, "table-id", request);

        ArgumentCaptor<JsonNode> patch = ArgumentCaptor.forClass(JsonNode.class);
        verify(openMetadataClient).patchTable(eq("table-id"), patch.capture());
        assertEquals("add", patch.getValue().get(0).get("op").asText());
        assertEquals("add", patch.getValue().get(1).get("op").asText());
        assertEquals("replace", patch.getValue().get(2).get("op").asText());
        assertEquals("replace", patch.getValue().get(3).get("op").asText());
        assertEquals("add", patch.getValue().get(4).get("op").asText());
        assertEquals("PII.Sensitive", patch.getValue().get(3).get("value").get(0).get("tagFQN").asText());
        assertEquals("null", patch.getValue().get(2).get("value").toString());
    }

    @Test
    void rejectsAProfileFromAnotherOpenMetadataService() {
        stubReadySource();
        OpenMetadataTable table = table("foreign-table", "orders");
        table.setServiceFullyQualifiedName("other");
        when(openMetadataClient.getTable("foreign-table")).thenReturn(table);

        assertThrows(RuntimeException.class, () -> service().getTable(42L, "foreign-table"));
    }

    private DataExplorationService service() {
        return new DataExplorationService(dataSourceDao, metadataBindingDao, openMetadataClient, catalogService);
    }

    private DataExplorationService service(OmReadCache omReadCache) {
        OpenMetadataProperties properties = new OpenMetadataProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://openmetadata.test/api");
        properties.setToken("test-token");
        return new DataExplorationService(
                dataSourceDao,
                metadataBindingDao,
                openMetadataClient,
                catalogService,
                OpenMetadataConfigResolver.fixed(properties),
                null,
                omReadCache);
    }

    private OmReadCache redisCache(Map<String, String> values) {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);
        when(operations.get(anyString())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        when(operations.setIfAbsent(anyString(), anyString())).thenAnswer(invocation -> values.putIfAbsent(
                invocation.getArgument(0), invocation.getArgument(1)) == null);
        doAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(operations).set(anyString(), anyString(), any(Duration.class));
        return OmReadCache.redis(
                new StringRedisMetadataStore(redisTemplate, new ObjectMapper()),
                Duration.ofMinutes(10),
                Duration.ofMinutes(15));
    }

    private static String scopeLabel(String scope) {
        if ("domain-a".equals(scope)) {
            return "a";
        }
        return "domain-b".equals(scope) ? "b" : "all";
    }

    private static long scopeMetric(String scope) {
        return switch (scopeLabel(scope)) {
            case "a" -> 10L;
            case "b" -> 20L;
            default -> 30L;
        };
    }

    private void stubReadySource() {
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(metadataBindingDao.queryByDataSourceId(42L)).thenReturn(binding());
    }

    private static DataSource source() {
        DataSource source = new DataSource();
        source.setId(42L);
        source.setDbType(org.apache.seatunnel.web.spi.enums.DbType.MYSQL);
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

    private static OpenMetadataDatabaseSchema schema(String id, String fqn) {
        OpenMetadataDatabaseSchema schema = new OpenMetadataDatabaseSchema();
        schema.setId(id);
        schema.setName("public");
        schema.setFullyQualifiedName(fqn);
        schema.setDatabaseFullyQualifiedName("st_ds_42.orders");
        schema.setServiceFullyQualifiedName("st_ds_42");
        return schema;
    }

    private static OpenMetadataTable table(String id, String name) {
        return tableWithFqn(id, name, "st_ds_42.orders.public.orders");
    }

    private static OpenMetadataTable tableWithFqn(String id, String name, String fqn) {
        OpenMetadataTable table = new OpenMetadataTable();
        table.setId(id);
        table.setName(name);
        table.setFullyQualifiedName(fqn);
        table.setServiceFullyQualifiedName("st_ds_42");
        table.setTableType("Regular");
        return table;
    }

    private static OpenMetadataColumn column(String name, String dataType, String constraint) {
        OpenMetadataColumn column = new OpenMetadataColumn();
        column.setName(name);
        column.setDataType(dataType);
        column.setConstraint(constraint);
        return column;
    }
}
