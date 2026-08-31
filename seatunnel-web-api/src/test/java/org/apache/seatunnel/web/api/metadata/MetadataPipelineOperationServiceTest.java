package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorAdapter;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorRegistry;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataEntity;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabase;
import org.apache.seatunnel.web.api.service.MetadataBindingCommandService;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataRunStatus;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataPipelineOperationServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private MetadataBindingDao bindingDao;
    @Mock private DataSourceDao dataSourceDao;
    @Mock private MetadataConnectorRegistry connectorRegistry;
    @Mock private MetadataConnectorAdapter connectorAdapter;
    @Mock private OpenMetadataClient openMetadataClient;
    @Mock private MetadataBindingCommandService metadataBindingCommandService;

    @Test
    void manualScanReservesTheExistingBindingAndTriggersOnlyOpenMetadata() {
        MetadataSourceBinding binding = binding(0L);
        MetadataSourceBinding reserved = binding(1L);
        reserved.setScanStatus(MetadataRunStatus.QUEUED);
        stubReady(binding, reserved);
        when(bindingDao.reserveRun(eq(1L), eq(0L), eq(true), eq(1L), any())).thenReturn(true);
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(1L))).thenReturn(true);

        service().triggerScan(42L);

        verify(openMetadataClient).triggerIngestionPipeline("meta-id");
        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(1L));
        assertEquals(1L, saved.getValue().getMetadataTriggeredVersion());
        assertEquals(MetadataRunStatus.QUEUED, saved.getValue().getScanStatus());
    }

    @Test
    void metadataReconcileOnlyMarksTheExistingBindingPending() {
        when(dataSourceDao.queryById(42L)).thenReturn(source());

        service().reconcileMetadata(42L);

        verify(metadataBindingCommandService).markConfigurationChanged(42L);
    }

    @Test
    void explorationRejectsADatabaseOutsideTheBindingService() {
        MetadataSourceBinding binding = binding(0L);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(bindingDao.queryByDataSourceId(42L)).thenReturn(binding);
        when(openMetadataClient.findDatabase("another_service.orders")).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> service().triggerExploration(42L, "another_service.orders"));
    }

    @Test
    void explorationRejectsAnExistingDatabaseOwnedByAnotherService() {
        MetadataSourceBinding binding = binding(0L);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(bindingDao.queryByDataSourceId(42L)).thenReturn(binding);
        when(openMetadataClient.findDatabase("other_service.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "other_service.orders", "other_service")));

        assertThrows(RuntimeException.class, () -> service().triggerExploration(42L, "other_service.orders"));
    }

    @Test
    void explorationUpdatesProfilerFilterThenDeploysAndTriggersTheReturnedPipeline() {
        MetadataSourceBinding binding = binding(0L);
        MetadataSourceBinding reserved = binding(1L);
        reserved.setProfileStatus(MetadataRunStatus.QUEUED);
        DataSource source = source();
        stubReady(binding, reserved);
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "st_ds_42.orders", "st_ds_42")));
        when(bindingDao.reserveRun(eq(1L), eq(0L), eq(false), isNull(), any())).thenReturn(true);
        when(connectorRegistry.require(DbType.DORIS)).thenReturn(connectorAdapter);
        when(connectorAdapter.profilerPipelineRequest(anyString(), anyString(), anyString(), eq("st_ds_42.orders")))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("profile-updated", "st_ds_42.st_ds_42_profiler"));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(1L))).thenReturn(true);

        service().triggerExploration(42L, "st_ds_42.orders");

        verify(openMetadataClient).deployIngestionPipeline("profile-updated");
        verify(openMetadataClient).enableIngestionPipeline("profile-updated");
        verify(openMetadataClient).triggerIngestionPipeline("profile-updated");
    }

    @Test
    void explorationReservationReturnsBeforeOpenMetadataPipelineOperations() {
        MetadataSourceBinding binding = binding(0L);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(bindingDao.queryByDataSourceId(42L)).thenReturn(binding);
        when(bindingDao.reserveRun(eq(1L), eq(0L), eq(false), isNull(), any())).thenReturn(true);

        MetadataPipelineOperationService.ExplorationReservation reservation =
                service().reserveExploration(42L, "st_ds_42.orders");

        assertEquals(1L, reservation.reservedVersion());
        assertEquals(42L, reservation.dataSourceId());
        verify(openMetadataClient, org.mockito.Mockito.never()).findDatabase(anyString());
    }

    @Test
    void explorationCompletionRestoresRunningStateAfterAStatusRefreshVersionBump() {
        Date reservationTime = new Date(1_700_001_000_000L);
        MetadataSourceBinding binding = binding(2L);
        binding.setProfileLastRunTime(reservationTime);
        when(bindingDao.queryById(1L)).thenReturn(binding);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "st_ds_42.orders", "st_ds_42")));
        when(openMetadataClient.listIngestionPipelineRuns(anyString(), eq(1))).thenReturn(List.of());
        when(connectorRegistry.require(DbType.DORIS)).thenReturn(connectorAdapter);
        when(connectorAdapter.profilerPipelineRequest(anyString(), anyString(), anyString(), eq("st_ds_42.orders")))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("profile-updated", "st_ds_42.st_ds_42_profiler"));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(2L))).thenReturn(true);

        service().executeExploration(new MetadataPipelineOperationService.ExplorationReservation(
                1L, 42L, "st_ds_42.orders", 1L, reservationTime));

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(2L));
        assertEquals(MetadataRunStatus.RUNNING, saved.getValue().getProfileStatus());
    }

    @Test
    void explorationFailureIsPersistedWhenDatabaseTruncatesReservationTime() {
        Date reservationTime = new Date(1_700_001_000_900L);
        MetadataSourceBinding binding = binding(2L);
        binding.setProfileStatus(MetadataRunStatus.QUEUED);
        binding.setProfileLastRunTime(new Date(1_700_001_000_000L));
        when(bindingDao.queryById(1L)).thenReturn(binding);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "st_ds_42.orders", "st_ds_42")));
        when(openMetadataClient.listIngestionPipelineRuns(anyString(), eq(1))).thenReturn(List.of());
        when(connectorRegistry.require(DbType.DORIS)).thenReturn(connectorAdapter);
        when(connectorAdapter.profilerPipelineRequest(anyString(), anyString(), anyString(), eq("st_ds_42.orders")))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("profile-updated", "st_ds_42.st_ds_42_profiler"));
        doThrow(new MetadataIntegrationException(MetadataErrorCode.OM_PIPELINE_DEPLOY_ERROR, "deploy failed"))
                .when(openMetadataClient).deployIngestionPipeline("profile-updated");
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(2L))).thenReturn(true);

        service().executeExploration(new MetadataPipelineOperationService.ExplorationReservation(
                1L, 42L, "st_ds_42.orders", 1L, reservationTime));

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(2L));
        assertEquals(MetadataRunStatus.FAILED, saved.getValue().getProfileStatus());
        assertEquals(MetadataErrorCode.OM_PIPELINE_DEPLOY_ERROR.name(), saved.getValue().getProfileLastError());
    }

    private void stubReady(MetadataSourceBinding binding, MetadataSourceBinding reserved) {
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(bindingDao.queryByDataSourceId(42L)).thenReturn(binding);
        when(openMetadataClient.listIngestionPipelineRuns(anyString(), eq(1))).thenReturn(List.of());
        when(bindingDao.queryById(1L)).thenReturn(reserved);
    }

    private MetadataPipelineOperationService service() {
        OpenMetadataProperties properties = new OpenMetadataProperties();
        properties.setEnabled(true);
        return new MetadataPipelineOperationService(
                properties,
                bindingDao,
                dataSourceDao,
                connectorRegistry,
                openMetadataClient,
                metadataBindingCommandService);
    }

    private static MetadataSourceBinding binding(Long version) {
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setId(1L);
        binding.setDataSourceId(42L);
        binding.setDesiredState(MetadataDesiredState.ACTIVE);
        binding.setSyncStatus(MetadataSyncStatus.READY);
        binding.setMetadataTriggeredVersion(0L);
        binding.setSyncedConfigVersion(1L);
        binding.setScanStatus(MetadataRunStatus.NEVER);
        binding.setProfileStatus(MetadataRunStatus.NEVER);
        binding.setOmServiceId("svc-id");
        binding.setOmServiceFqn("st_ds_42");
        binding.setOmMetadataPipelineId("meta-id");
        binding.setOmMetadataPipelineFqn("st_ds_42.st_ds_42_metadata");
        binding.setOmProfilerPipelineId("profile-id");
        binding.setOmProfilerPipelineFqn("st_ds_42.st_ds_42_profiler");
        binding.setVersion(version);
        return binding;
    }

    private static DataSource source() {
        DataSource source = new DataSource();
        source.setId(42L);
        source.setDbType(DbType.DORIS);
        source.setStatus(DataSourceLifecycleStatus.ENABLED);
        return source;
    }
}
