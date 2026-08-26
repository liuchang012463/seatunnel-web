package org.apache.seatunnel.web.api.metadata;

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
import org.apache.seatunnel.web.common.enums.MetadataRunStatus;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationColumnProfileVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationProfileVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationTableDetailVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationTablePageVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationTableVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
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
        assertEquals("public.orders", request.getValue().get("table_path"));
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
        OpenMetadataTable table = new OpenMetadataTable();
        table.setId(id);
        table.setName(name);
        table.setFullyQualifiedName("st_ds_42.orders.public.orders");
        table.setServiceFullyQualifiedName("st_ds_42");
        table.setTableType("Regular");
        return table;
    }
}
