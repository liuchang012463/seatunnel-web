package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorAdapter;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorRegistry;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataEntity;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataSourceReconcilerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private MetadataBindingDao bindingDao;
    @Mock private DataSourceDao dataSourceDao;
    @Mock private MetadataConnectorRegistry registry;
    @Mock private MetadataConnectorAdapter adapter;
    @Mock private OpenMetadataClient openMetadataClient;

    @Test
    void synchronizesOnlyTheClaimedVersionAndMakesTwo11210PipelinesReady() {
        MetadataSourceBinding candidate = binding(1L, MetadataDesiredState.ACTIVE, 1L, 0L);
        MetadataSourceBinding live = binding(1L, MetadataDesiredState.ACTIVE, 1L, 1L);
        DataSource source = source();
        stubCandidate(candidate, live);
        when(dataSourceDao.queryById(42L)).thenReturn(source);
        when(registry.require(DbType.MYSQL)).thenReturn(adapter);
        when(adapter.databaseServiceRequest(eq(source), eq("st_ds_42"))).thenReturn(JSON.createObjectNode());
        when(adapter.metadataPipelineRequest(eq("st_ds_42_metadata"), eq("svc"), eq("st_ds_42")))
                .thenReturn(JSON.createObjectNode());
        when(adapter.profilerPipelineRequest(eq("st_ds_42_profiler"), eq("svc"), eq("st_ds_42")))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertDatabaseService(any())).thenReturn(new OpenMetadataEntity("svc", "st_ds_42"));
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("meta", "st_ds_42_metadata"))
                .thenReturn(new OpenMetadataEntity("prof", "st_ds_42_profiler"));

        reconciler().reconcilePendingBindings();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateClaimed(saved.capture(), eq(1L));
        assertEquals(MetadataSyncStatus.READY, saved.getValue().getSyncStatus());
        assertEquals(1L, saved.getValue().getSyncedConfigVersion());
        assertEquals("svc", saved.getValue().getOmServiceId());
        verify(openMetadataClient).deployIngestionPipeline("meta");
        verify(openMetadataClient).deployIngestionPipeline("prof");
    }

    @Test
    void preservesANewerLocalConfigurationForTheNextReconcile() {
        MetadataSourceBinding candidate = binding(1L, MetadataDesiredState.ACTIVE, 1L, 0L);
        MetadataSourceBinding live = binding(1L, MetadataDesiredState.ACTIVE, 2L, 1L);
        DataSource source = source();
        stubCandidate(candidate, live);
        when(dataSourceDao.queryById(42L)).thenReturn(source);
        when(registry.require(DbType.MYSQL)).thenReturn(adapter);
        when(adapter.databaseServiceRequest(any(), any())).thenReturn(JSON.createObjectNode());
        when(adapter.metadataPipelineRequest(any(), any(), any())).thenReturn(JSON.createObjectNode());
        when(adapter.profilerPipelineRequest(any(), any(), any())).thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertDatabaseService(any())).thenReturn(new OpenMetadataEntity("svc", "st_ds_42"));
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("meta", "st_ds_42_metadata"))
                .thenReturn(new OpenMetadataEntity("prof", "st_ds_42_profiler"));

        reconciler().reconcilePendingBindings();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateClaimed(saved.capture(), eq(1L));
        assertEquals(MetadataSyncStatus.PENDING, saved.getValue().getSyncStatus());
        assertEquals(0L, saved.getValue().getSyncedConfigVersion());
    }

    @Test
    void recordsSanitizedRetryStateWhenAConnectorIsDeferred() {
        MetadataSourceBinding candidate = binding(1L, MetadataDesiredState.ACTIVE, 1L, 0L);
        MetadataSourceBinding live = binding(1L, MetadataDesiredState.ACTIVE, 1L, 1L);
        stubCandidate(candidate, live);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        doThrow(new MetadataIntegrationException(MetadataErrorCode.CONNECTOR_NOT_SUPPORTED, "not supported"))
                .when(registry).require(DbType.MYSQL);

        reconciler().reconcilePendingBindings();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateClaimed(saved.capture(), eq(1L));
        assertEquals(MetadataSyncStatus.ERROR, saved.getValue().getSyncStatus());
        assertEquals("CONNECTOR_NOT_SUPPORTED", saved.getValue().getLastSyncErrorCode());
        assertEquals(1, saved.getValue().getRetryCount());
    }

    @Test
    void deletesBindingAndLocalDataSourceOnlyAfterOmCleanup() {
        MetadataSourceBinding candidate = binding(1L, MetadataDesiredState.DELETED, 2L, 0L);
        candidate.setOmMetadataPipelineId("meta");
        candidate.setOmProfilerPipelineId("prof");
        candidate.setOmServiceId("svc");
        stubCandidate(candidate, null);
        when(bindingDao.deleteClaimed(1L, 1L)).thenReturn(true);

        reconciler().reconcilePendingBindings();

        verify(openMetadataClient).deleteIngestionPipeline("meta");
        verify(openMetadataClient).deleteIngestionPipeline("prof");
        verify(openMetadataClient).deleteDatabaseServiceRecursively("svc");
        verify(dataSourceDao).deleteById(42L);
    }

    @Test
    void adoptsStableNamesForDeletionWhenAnOlderBindingHasNoOmIds() {
        MetadataSourceBinding candidate = binding(1L, MetadataDesiredState.DELETED, 2L, 0L);
        stubCandidate(candidate, null);
        when(openMetadataClient.findIngestionPipeline("st_ds_42_metadata"))
                .thenReturn(Optional.of(new OpenMetadataEntity("meta", "st_ds_42_metadata")));
        when(openMetadataClient.findIngestionPipeline("st_ds_42_profiler"))
                .thenReturn(Optional.of(new OpenMetadataEntity("prof", "st_ds_42_profiler")));
        when(openMetadataClient.findDatabaseService("st_ds_42"))
                .thenReturn(Optional.of(new OpenMetadataEntity("svc", "st_ds_42")));
        when(bindingDao.deleteClaimed(1L, 1L)).thenReturn(true);

        reconciler().reconcilePendingBindings();

        verify(openMetadataClient).deleteIngestionPipeline("meta");
        verify(openMetadataClient).deleteIngestionPipeline("prof");
        verify(openMetadataClient).deleteDatabaseServiceRecursively("svc");
        verify(dataSourceDao).deleteById(42L);
    }

    private MetadataSourceReconciler reconciler() {
        MetadataReconcileProperties properties = new MetadataReconcileProperties();
        properties.setBatchSize(10);
        return new MetadataSourceReconciler(bindingDao, dataSourceDao, registry, openMetadataClient, properties);
    }

    private void stubCandidate(MetadataSourceBinding candidate, MetadataSourceBinding live) {
        when(bindingDao.queryReconcileCandidates(any(Date.class), any(Date.class), eq(10))).thenReturn(List.of(candidate));
        when(bindingDao.tryClaim(eq(1L), eq(0L), any(Date.class), any(Date.class))).thenReturn(true);
        if (live != null) {
            // The conditional DAO claim has already transitioned the persisted row.
            live.setSyncStatus(MetadataSyncStatus.SYNCING);
            when(bindingDao.queryById(1L)).thenReturn(live);
        }
    }

    private static MetadataSourceBinding binding(
            Long id, MetadataDesiredState desiredState, Long configVersion, Long version) {
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setId(id);
        binding.setDataSourceId(42L);
        binding.setDesiredState(desiredState);
        binding.setSyncStatus(desiredState == MetadataDesiredState.DELETED
                ? MetadataSyncStatus.DELETING : MetadataSyncStatus.PENDING);
        binding.setConfigVersion(configVersion);
        binding.setSyncedConfigVersion(0L);
        binding.setVersion(version);
        binding.setRetryCount(0);
        return binding;
    }

    private static DataSource source() {
        DataSource source = new DataSource();
        source.setId(42L);
        source.setName("orders");
        source.setDbType(DbType.MYSQL);
        source.setConnectionParams("{\"url\":\"jdbc:mysql://db:3306/orders\",\"user\":\"reader\",\"password\":\"secret\"}");
        return source;
    }
}
