package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorAdapter;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorRegistry;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataEntity;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabase;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabaseSchema;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPipelineRun;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
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
        stubProfilerCapable();
        when(openMetadataClient.findDatabase("another_service.orders")).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> service().triggerExploration(42L, "another_service.orders"));
    }

    @Test
    void explorationRejectsAnExistingDatabaseOwnedByAnotherService() {
        MetadataSourceBinding binding = binding(0L);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(bindingDao.queryByDataSourceId(42L)).thenReturn(binding);
        stubProfilerCapable();
        when(openMetadataClient.findDatabase("other_service.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "other_service.orders", "other_service")));

        assertThrows(RuntimeException.class, () -> service().triggerExploration(42L, "other_service.orders"));
    }

    @Test
    void explorationReservationRejectsDataSourcesWithoutAProfiler() {
        MetadataSourceBinding binding = binding(0L);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(bindingDao.queryByDataSourceId(42L)).thenReturn(binding);
        when(connectorRegistry.find(DbType.DORIS)).thenReturn(Optional.of(connectorAdapter));
        when(connectorAdapter.supportsProfiler()).thenReturn(false);

        assertThrows(RuntimeException.class, () -> service().reserveExploration(42L, "st_ds_42.orders"));
        verify(bindingDao, never()).reserveRun(anyLong(), anyLong(), anyBoolean(), any(), any());
    }

    @Test
    void explorationReservationLeavesMissingConnectorsToTheBindingGate() {
        MetadataSourceBinding binding = binding(0L);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(bindingDao.queryByDataSourceId(42L)).thenReturn(binding);
        when(connectorRegistry.find(DbType.DORIS)).thenReturn(Optional.empty());
        when(bindingDao.reserveRun(eq(1L), eq(0L), eq(false), isNull(), any(), anyString())).thenReturn(true);

        // A type without a connector never reaches a READY binding, so that gate already
        // blocks it; the profiler guard only rejects adapters that exist and say no.
        assertNotNull(service().reserveExploration(42L, "st_ds_42.orders"));
    }

    @Test
    void explorationUpdatesProfilerFilterThenDeploysAndTriggersTheReturnedPipeline() {
        MetadataSourceBinding binding = binding(0L);
        MetadataSourceBinding reserved = binding(1L);
        reserved.setProfileStatus(MetadataRunStatus.QUEUED);
        DataSource source = source();
        stubReady(binding, reserved);
        stubProfilerCapable();
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "st_ds_42.orders", "st_ds_42")));
        when(bindingDao.reserveRun(eq(1L), eq(0L), eq(false), isNull(), any(), anyString()))
                .thenAnswer(invocation -> {
                    reserved.setProfileRunReservationToken(invocation.getArgument(5));
                    reserved.setProfileRunBaselineCaptured(false);
                    reserved.setProfileLastRunTime(invocation.getArgument(4));
                    return true;
                });
        when(connectorRegistry.require(DbType.DORIS)).thenReturn(connectorAdapter);
        when(connectorAdapter.profilerPipelineRequest(anyString(), anyString(), anyString(), eq("st_ds_42.orders")))
                .thenReturn(JSON.createObjectNode());
        when(connectorAdapter.autoClassificationPipelineRequest(
                anyString(), anyString(), anyString(), eq("st_ds_42.orders"), nullable(String.class)))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("profile-updated", "st_ds_42.st_ds_42_profiler"))
                .thenReturn(new OpenMetadataEntity("sample-updated", "st_ds_42.st_ds_42_auto_classification"));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), anyLong())).thenReturn(true);

        service().triggerExploration(42L, "st_ds_42.orders");

        verify(openMetadataClient).deployIngestionPipeline("profile-updated");
        verify(openMetadataClient).enableIngestionPipeline("profile-updated");
        verify(openMetadataClient).triggerIngestionPipeline("profile-updated");
        verify(openMetadataClient).deployIngestionPipeline("sample-updated");
        verify(openMetadataClient).enableIngestionPipeline("sample-updated");
        verify(openMetadataClient).triggerIngestionPipeline("sample-updated");
    }

    @Test
    void retriesOnlyTheFailedSampleTriggerAndDoesNotRepeatTheSuccessfulProfilerTrigger() {
        MetadataSourceBinding binding = binding(0L);
        MetadataSourceBinding reserved = binding(1L);
        reserved.setProfileStatus(MetadataRunStatus.QUEUED);
        stubReady(binding, reserved);
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "st_ds_42.orders", "st_ds_42")));
        when(bindingDao.reserveRun(eq(1L), eq(0L), eq(false), isNull(), any(), anyString()))
                .thenAnswer(invocation -> {
                    reserved.setProfileRunReservationToken(invocation.getArgument(5));
                    reserved.setProfileRunBaselineCaptured(false);
                    reserved.setProfileLastRunTime(invocation.getArgument(4));
                    return true;
                });
        when(connectorRegistry.require(DbType.DORIS)).thenReturn(connectorAdapter);
        when(connectorAdapter.profilerPipelineRequest(anyString(), anyString(), anyString(), eq("st_ds_42.orders")))
                .thenReturn(JSON.createObjectNode());
        when(connectorAdapter.autoClassificationPipelineRequest(
                anyString(), anyString(), anyString(), eq("st_ds_42.orders"), nullable(String.class)))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("profile-updated", "st_ds_42.st_ds_42_profiler"))
                .thenReturn(new OpenMetadataEntity("sample-updated", "st_ds_42.st_ds_42_auto_classification"));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), anyLong())).thenReturn(true);
        int[] sampleAttempts = {0};
        doAnswer(invocation -> {
            if ("sample-updated".equals(invocation.getArgument(0)) && ++sampleAttempts[0] == 1) {
                throw new MetadataIntegrationException(
                        MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR,
                        "transient trigger rejection",
                        null,
                        400);
            }
            return null;
        }).when(openMetadataClient).triggerIngestionPipeline(anyString());

        MetadataPipelineOperationService operationService = spy(service());
        doNothing().when(operationService).waitBeforePipelineTriggerRetry(anyLong());

        assertTrue(operationService.triggerExploration(42L, "st_ds_42.orders"));

        verify(openMetadataClient, times(1)).triggerIngestionPipeline("profile-updated");
        verify(openMetadataClient, times(2)).triggerIngestionPipeline("sample-updated");
        verify(operationService).waitBeforePipelineTriggerRetry(2_000L);
    }

    @Test
    void doesNotRetryWhenANewSampleRunAppearsAfterARejectedTrigger() {
        MetadataSourceBinding binding = binding(0L);
        MetadataSourceBinding reserved = binding(1L);
        reserved.setProfileStatus(MetadataRunStatus.QUEUED);
        stubReady(binding, reserved);
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "st_ds_42.orders", "st_ds_42")));
        when(bindingDao.reserveRun(eq(1L), eq(0L), eq(false), isNull(), any(), anyString()))
                .thenAnswer(invocation -> {
                    reserved.setProfileRunReservationToken(invocation.getArgument(5));
                    reserved.setProfileRunBaselineCaptured(false);
                    reserved.setProfileLastRunTime(invocation.getArgument(4));
                    return true;
                });
        String sampleFqn = "st_ds_42.st_ds_42_auto_classification";
        when(openMetadataClient.listIngestionPipelineRuns(eq(sampleFqn), eq(1)))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "sample-run-after-baseline", "queued", 1_700_001_001L, 1_700_001_001L, null, 0)));
        when(connectorRegistry.require(DbType.DORIS)).thenReturn(connectorAdapter);
        when(connectorAdapter.profilerPipelineRequest(anyString(), anyString(), anyString(), eq("st_ds_42.orders")))
                .thenReturn(JSON.createObjectNode());
        when(connectorAdapter.autoClassificationPipelineRequest(
                anyString(), anyString(), anyString(), eq("st_ds_42.orders"), nullable(String.class)))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("profile-updated", "st_ds_42.st_ds_42_profiler"))
                .thenReturn(new OpenMetadataEntity("sample-updated", sampleFqn));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), anyLong())).thenReturn(true);
        doAnswer(invocation -> {
            if ("sample-updated".equals(invocation.getArgument(0))) {
                throw new MetadataIntegrationException(
                        MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR,
                        "transient trigger rejection",
                        null,
                        400);
            }
            return null;
        }).when(openMetadataClient).triggerIngestionPipeline(anyString());

        MetadataPipelineOperationService operationService = spy(service());
        doNothing().when(operationService).waitBeforePipelineTriggerRetry(anyLong());

        assertTrue(operationService.triggerExploration(42L, "st_ds_42.orders"));

        verify(openMetadataClient, times(1)).triggerIngestionPipeline("profile-updated");
        verify(openMetadataClient, times(1)).triggerIngestionPipeline("sample-updated");
        verify(operationService).waitBeforePipelineTriggerRetry(2_000L);
    }

    @Test
    void startsBothInitialPipelinesWhenSdkTimeoutsExhaustTheRetryWindow() {
        MetadataSourceBinding binding = binding(0L);
        MetadataSourceBinding reserved = binding(1L);
        reserved.setProfileStatus(MetadataRunStatus.QUEUED);
        stubReady(binding, reserved);
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "st_ds_42.orders", "st_ds_42")));
        when(bindingDao.reserveRun(eq(1L), eq(0L), eq(false), isNull(), any(), anyString()))
                .thenAnswer(invocation -> {
                    reserved.setProfileRunReservationToken(invocation.getArgument(5));
                    reserved.setProfileRunBaselineCaptured(false);
                    reserved.setProfileLastRunTime(invocation.getArgument(4));
                    return true;
                });
        when(connectorRegistry.require(DbType.DORIS)).thenReturn(connectorAdapter);
        when(connectorAdapter.profilerPipelineRequest(anyString(), anyString(), anyString(), eq("st_ds_42.orders")))
                .thenReturn(JSON.createObjectNode());
        when(connectorAdapter.autoClassificationPipelineRequest(
                anyString(), anyString(), anyString(), eq("st_ds_42.orders"), nullable(String.class)))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("profile-updated", "st_ds_42.st_ds_42_profiler"))
                .thenReturn(new OpenMetadataEntity("sample-updated", "st_ds_42.st_ds_42_auto_classification"));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), anyLong())).thenReturn(true);

        OpenMetadataProperties omProperties = new OpenMetadataProperties();
        omProperties.setEnabled(true);
        omProperties.setBaseUrl("http://127.0.0.1:8585/api");
        omProperties.setToken("test-token");
        omProperties.setConnectTimeoutMs(20_000);
        omProperties.setReadTimeoutMs(20_000);
        MetadataStatusProperties statusProperties = new MetadataStatusProperties();
        statusProperties.setExplorationPreparationTimeoutSeconds(60L);
        statusProperties.setExplorationTriggerRetryTimeoutSeconds(1_200L);
        AtomicLong fakeNow = new AtomicLong();
        doAnswer(invocation -> {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR, "temporarily unavailable", null, 400);
        }).when(openMetadataClient).triggerIngestionPipeline(anyString());

        MetadataPipelineOperationService operationService = spy(service(omProperties));
        doAnswer(invocation -> fakeNow.get()).when(operationService).monotonicNowNanos();
        operationService.setMetadataStatusProperties(statusProperties);

        assertThrows(RuntimeException.class, () -> operationService.triggerExploration(42L, "st_ds_42.orders"));

        verify(openMetadataClient, times(1)).triggerIngestionPipeline("profile-updated");
        verify(openMetadataClient, times(1)).triggerIngestionPipeline("sample-updated");
        verify(operationService, never()).waitBeforePipelineTriggerRetry(anyLong());
    }

    @Test
    void sharesOneRetryDeadlineAcrossProfilerAndSampler() {
        MetadataSourceBinding binding = binding(0L);
        MetadataSourceBinding reserved = binding(1L);
        reserved.setProfileStatus(MetadataRunStatus.QUEUED);
        stubReady(binding, reserved);
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "st_ds_42.orders", "st_ds_42")));
        when(bindingDao.reserveRun(eq(1L), eq(0L), eq(false), isNull(), any(), anyString()))
                .thenAnswer(invocation -> {
                    reserved.setProfileRunReservationToken(invocation.getArgument(5));
                    reserved.setProfileRunBaselineCaptured(false);
                    reserved.setProfileLastRunTime(invocation.getArgument(4));
                    return true;
                });
        when(connectorRegistry.require(DbType.DORIS)).thenReturn(connectorAdapter);
        when(connectorAdapter.profilerPipelineRequest(anyString(), anyString(), anyString(), eq("st_ds_42.orders")))
                .thenReturn(JSON.createObjectNode());
        when(connectorAdapter.autoClassificationPipelineRequest(
                anyString(), anyString(), anyString(), eq("st_ds_42.orders"), nullable(String.class)))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("profile-updated", "st_ds_42.st_ds_42_profiler"))
                .thenReturn(new OpenMetadataEntity("sample-updated", "st_ds_42.st_ds_42_auto_classification"));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), anyLong())).thenReturn(true);

        AtomicLong fakeNow = new AtomicLong();
        int[] profilerAttempts = {0};
        doAnswer(invocation -> {
            String pipelineId = invocation.getArgument(0);
            if ("profile-updated".equals(pipelineId)) {
                if (++profilerAttempts[0] == 1) {
                    throw new MetadataIntegrationException(
                            MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR,
                            "transient profiler trigger rejection",
                            null,
                            400);
                }
                fakeNow.set(TimeUnit.SECONDS.toNanos(31L));
                return null;
            }
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR,
                    "transient sample trigger rejection",
                    null,
                    400);
        }).when(openMetadataClient).triggerIngestionPipeline(anyString());

        OpenMetadataProperties omProperties = new OpenMetadataProperties();
        omProperties.setEnabled(true);
        omProperties.setBaseUrl("http://127.0.0.1:8585/api");
        omProperties.setToken("test-token");
        omProperties.setConnectTimeoutMs(0);
        omProperties.setReadTimeoutMs(0);
        MetadataStatusProperties statusProperties = new MetadataStatusProperties();
        statusProperties.setExplorationPreparationTimeoutSeconds(600L);
        statusProperties.setExplorationTriggerRetryTimeoutSeconds(30L);
        MetadataPipelineOperationService operationService = spy(service(omProperties));
        doAnswer(invocation -> fakeNow.get()).when(operationService).monotonicNowNanos();
        doNothing().when(operationService).waitBeforePipelineTriggerRetry(anyLong());
        operationService.setMetadataStatusProperties(statusProperties);

        assertThrows(RuntimeException.class, () -> operationService.triggerExploration(42L, "st_ds_42.orders"));

        verify(openMetadataClient, times(2)).triggerIngestionPipeline("profile-updated");
        verify(openMetadataClient, times(2)).triggerIngestionPipeline("sample-updated");
        verify(operationService).waitBeforePipelineTriggerRetry(2_000L);
    }

    @Test
    void doesNotRetryARejectedTriggerWhenTheFailureIsNotHttp400() {
        MetadataSourceBinding binding = binding(0L);
        MetadataSourceBinding reserved = binding(1L);
        reserved.setProfileStatus(MetadataRunStatus.QUEUED);
        stubReady(binding, reserved);
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "st_ds_42.orders", "st_ds_42")));
        when(bindingDao.reserveRun(eq(1L), eq(0L), eq(false), isNull(), any(), anyString()))
                .thenAnswer(invocation -> {
                    reserved.setProfileRunReservationToken(invocation.getArgument(5));
                    reserved.setProfileRunBaselineCaptured(false);
                    reserved.setProfileLastRunTime(invocation.getArgument(4));
                    return true;
                });
        when(connectorRegistry.require(DbType.DORIS)).thenReturn(connectorAdapter);
        when(connectorAdapter.profilerPipelineRequest(anyString(), anyString(), anyString(), eq("st_ds_42.orders")))
                .thenReturn(JSON.createObjectNode());
        when(connectorAdapter.autoClassificationPipelineRequest(
                anyString(), anyString(), anyString(), eq("st_ds_42.orders"), nullable(String.class)))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("profile-updated", "st_ds_42.st_ds_42_profiler"))
                .thenReturn(new OpenMetadataEntity("sample-updated", "st_ds_42.st_ds_42_auto_classification"));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), anyLong())).thenReturn(true);
        doAnswer(invocation -> {
            if ("sample-updated".equals(invocation.getArgument(0))) {
                throw new MetadataIntegrationException(
                        MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR,
                        "non-retryable trigger failure",
                        null,
                        503);
            }
            return null;
        }).when(openMetadataClient).triggerIngestionPipeline(anyString());

        MetadataPipelineOperationService operationService = spy(service());

        assertThrows(RuntimeException.class, () -> operationService.triggerExploration(42L, "st_ds_42.orders"));

        verify(openMetadataClient, times(1)).triggerIngestionPipeline("profile-updated");
        verify(openMetadataClient, times(1)).triggerIngestionPipeline("sample-updated");
        verify(operationService, never()).waitBeforePipelineTriggerRetry(anyLong());
    }

    @Test
    void boundsTheNumberOfHttp400TriggerRetries() {
        MetadataSourceBinding binding = binding(0L);
        MetadataSourceBinding reserved = binding(1L);
        reserved.setProfileStatus(MetadataRunStatus.QUEUED);
        stubReady(binding, reserved);
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "st_ds_42.orders", "st_ds_42")));
        when(bindingDao.reserveRun(eq(1L), eq(0L), eq(false), isNull(), any(), anyString()))
                .thenAnswer(invocation -> {
                    reserved.setProfileRunReservationToken(invocation.getArgument(5));
                    reserved.setProfileRunBaselineCaptured(false);
                    reserved.setProfileLastRunTime(invocation.getArgument(4));
                    return true;
                });
        when(connectorRegistry.require(DbType.DORIS)).thenReturn(connectorAdapter);
        when(connectorAdapter.profilerPipelineRequest(anyString(), anyString(), anyString(), eq("st_ds_42.orders")))
                .thenReturn(JSON.createObjectNode());
        when(connectorAdapter.autoClassificationPipelineRequest(
                anyString(), anyString(), anyString(), eq("st_ds_42.orders"), nullable(String.class)))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("profile-updated", "st_ds_42.st_ds_42_profiler"))
                .thenReturn(new OpenMetadataEntity("sample-updated", "st_ds_42.st_ds_42_auto_classification"));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), anyLong())).thenReturn(true);
        doAnswer(invocation -> {
            if ("sample-updated".equals(invocation.getArgument(0))) {
                throw new MetadataIntegrationException(
                        MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR,
                        "persistent trigger rejection",
                        null,
                        400);
            }
            return null;
        }).when(openMetadataClient).triggerIngestionPipeline(anyString());

        AtomicLong fakeNow = new AtomicLong();
        OpenMetadataProperties omProperties = new OpenMetadataProperties();
        omProperties.setEnabled(true);
        omProperties.setBaseUrl("http://127.0.0.1:8585/api");
        omProperties.setToken("test-token");
        omProperties.setConnectTimeoutMs(0);
        omProperties.setReadTimeoutMs(0);
        MetadataStatusProperties statusProperties = new MetadataStatusProperties();
        statusProperties.setExplorationPreparationTimeoutSeconds(600L);
        statusProperties.setExplorationTriggerRetryTimeoutSeconds(60L);
        MetadataPipelineOperationService operationService = spy(service(omProperties));
        doAnswer(invocation -> fakeNow.get()).when(operationService).monotonicNowNanos();
        doAnswer(invocation -> {
            fakeNow.addAndGet(TimeUnit.MILLISECONDS.toNanos(invocation.getArgument(0)));
            return null;
        }).when(operationService).waitBeforePipelineTriggerRetry(anyLong());
        operationService.setMetadataStatusProperties(statusProperties);

        assertThrows(RuntimeException.class, () -> operationService.triggerExploration(42L, "st_ds_42.orders"));

        verify(openMetadataClient, times(1)).triggerIngestionPipeline("profile-updated");
        verify(openMetadataClient, times(5)).triggerIngestionPipeline("sample-updated");
        verify(operationService, times(4)).waitBeforePipelineTriggerRetry(anyLong());
    }

    @Test
    void retriesBothPipelinesAfterFifteenMinutesWithLongSdkTimeouts() {
        MetadataSourceBinding binding = binding(0L);
        MetadataSourceBinding reserved = binding(1L);
        reserved.setProfileStatus(MetadataRunStatus.QUEUED);
        stubReady(binding, reserved);
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "st_ds_42.orders", "st_ds_42")));
        when(bindingDao.reserveRun(eq(1L), eq(0L), eq(false), isNull(), any(), anyString()))
                .thenAnswer(invocation -> {
                    reserved.setProfileRunReservationToken(invocation.getArgument(5));
                    reserved.setProfileRunBaselineCaptured(false);
                    reserved.setProfileLastRunTime(invocation.getArgument(4));
                    return true;
                });
        when(connectorRegistry.require(DbType.DORIS)).thenReturn(connectorAdapter);
        when(connectorAdapter.profilerPipelineRequest(anyString(), anyString(), anyString(), eq("st_ds_42.orders")))
                .thenReturn(JSON.createObjectNode());
        when(connectorAdapter.autoClassificationPipelineRequest(
                anyString(), anyString(), anyString(), eq("st_ds_42.orders"), nullable(String.class)))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("profile-updated", "st_ds_42.st_ds_42_profiler"))
                .thenReturn(new OpenMetadataEntity("sample-updated", "st_ds_42.st_ds_42_auto_classification"));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), anyLong())).thenReturn(true);
        AtomicLong fakeNow = new AtomicLong();
        int[] profilerAttempts = {0};
        int[] sampleAttempts = {0};
        long readyAt = TimeUnit.MINUTES.toNanos(15);
        doAnswer(invocation -> {
            String pipelineId = invocation.getArgument(0);
            if ("profile-updated".equals(pipelineId)) {
                profilerAttempts[0]++;
            } else {
                sampleAttempts[0]++;
            }
            if (fakeNow.get() < readyAt) {
                throw new MetadataIntegrationException(
                        MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR,
                        "managed pipeline is not ready",
                        null,
                        400);
            }
            return null;
        }).when(openMetadataClient).triggerIngestionPipeline(anyString());

        OpenMetadataProperties omProperties = new OpenMetadataProperties();
        omProperties.setEnabled(true);
        omProperties.setBaseUrl("http://127.0.0.1:8585/api");
        omProperties.setToken("test-token");
        omProperties.setConnectTimeoutMs(210_000);
        omProperties.setReadTimeoutMs(210_000);
        MetadataPipelineOperationService operationService = spy(service(omProperties));
        doAnswer(invocation -> fakeNow.get()).when(operationService).monotonicNowNanos();
        doAnswer(invocation -> {
            fakeNow.addAndGet(TimeUnit.MILLISECONDS.toNanos(invocation.getArgument(0)));
            return null;
        }).when(operationService).waitBeforePipelineTriggerRetry(anyLong());
        MetadataStatusProperties statusProperties = new MetadataStatusProperties();
        statusProperties.setExplorationPreparationTimeoutSeconds(1_800L);
        statusProperties.setExplorationTriggerRetryTimeoutSeconds(1_200L);
        operationService.setMetadataStatusProperties(statusProperties);

        assertTrue(operationService.triggerExploration(42L, "st_ds_42.orders"));

        assertTrue(fakeNow.get() >= readyAt);
        assertTrue(profilerAttempts[0] > 10);
        assertTrue(sampleAttempts[0] > 10);
    }

    @Test
    void explorationUsesTheSelectedSchemaAndListsSchemasForTheOwnedDatabase() {
        MetadataSourceBinding binding = binding(0L);
        MetadataSourceBinding reserved = binding(1L);
        reserved.setProfileStatus(MetadataRunStatus.QUEUED);
        stubReady(binding, reserved);
        stubProfilerCapable();
        String databaseFqn = "st_ds_42.kingbase";
        String schemaFqn = databaseFqn + ".public";
        when(openMetadataClient.findDatabase(databaseFqn))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", databaseFqn, "st_ds_42")));
        OpenMetadataDatabaseSchema schema = new OpenMetadataDatabaseSchema();
        schema.setId("schema-id");
        schema.setName("public");
        schema.setFullyQualifiedName(schemaFqn);
        schema.setDatabaseFullyQualifiedName(databaseFqn);
        schema.setServiceFullyQualifiedName("st_ds_42");
        doReturn(new OpenMetadataPage<>(List.of(schema), 1L, null))
                .when(openMetadataClient).listSchemasPage(databaseFqn, 1000, null);
        when(bindingDao.reserveRun(eq(1L), eq(0L), eq(false), isNull(), any(), anyString()))
                .thenAnswer(invocation -> {
                    reserved.setProfileRunReservationToken(invocation.getArgument(5));
                    reserved.setProfileRunBaselineCaptured(false);
                    reserved.setProfileLastRunTime(invocation.getArgument(4));
                    return true;
                });
        when(connectorRegistry.require(DbType.DORIS)).thenReturn(connectorAdapter);
        when(connectorAdapter.profilerPipelineRequest(
                anyString(), anyString(), anyString(), eq(databaseFqn), eq(schemaFqn)))
                .thenReturn(JSON.createObjectNode());
        when(connectorAdapter.autoClassificationPipelineRequest(
                anyString(), anyString(), anyString(), eq(databaseFqn), eq(schemaFqn)))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("profile-updated", "st_ds_42.st_ds_42_profiler"))
                .thenReturn(new OpenMetadataEntity("sample-updated", "st_ds_42.st_ds_42_auto_classification"));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), anyLong())).thenReturn(true);

        assertEquals("public", service().listSchemas(42L, databaseFqn).get(0).getLabel());
        service().triggerExploration(42L, databaseFqn, schemaFqn);

        verify(connectorAdapter).profilerPipelineRequest(
                anyString(), anyString(), anyString(), eq(databaseFqn), eq(schemaFqn));
        verify(connectorAdapter).autoClassificationPipelineRequest(
                anyString(), anyString(), anyString(), eq(databaseFqn), eq(schemaFqn));
    }

    @Test
    void explorationReservationReturnsBeforeOpenMetadataPipelineOperations() {
        MetadataSourceBinding binding = binding(0L);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(bindingDao.queryByDataSourceId(42L)).thenReturn(binding);
        stubProfilerCapable();
        when(bindingDao.reserveRun(eq(1L), eq(0L), eq(false), isNull(), any(), anyString())).thenReturn(true);

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
        binding.setProfileRunReservationToken("generation-1");
        when(bindingDao.queryById(1L)).thenReturn(binding);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "st_ds_42.orders", "st_ds_42")));
        when(openMetadataClient.listIngestionPipelineRuns(anyString(), eq(1))).thenReturn(List.of());
        when(connectorRegistry.require(DbType.DORIS)).thenReturn(connectorAdapter);
        when(connectorAdapter.profilerPipelineRequest(anyString(), anyString(), anyString(), eq("st_ds_42.orders")))
                .thenReturn(JSON.createObjectNode());
        when(connectorAdapter.autoClassificationPipelineRequest(
                anyString(), anyString(), anyString(), eq("st_ds_42.orders"), nullable(String.class)))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("profile-updated", "st_ds_42.st_ds_42_profiler"))
                .thenReturn(new OpenMetadataEntity("sample-updated", "st_ds_42.st_ds_42_auto_classification"));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), anyLong())).thenReturn(true);

        service().executeExploration(new MetadataPipelineOperationService.ExplorationReservation(
                1L, 42L, "st_ds_42.orders", null, 1L, reservationTime, "generation-1"));

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao, times(2)).updateIfVersion(saved.capture(), anyLong());
        assertEquals(MetadataRunStatus.RUNNING, saved.getAllValues().get(1).getProfileStatus());
    }

    @Test
    void explorationFailureIsPersistedWhenDatabaseTruncatesReservationTime() {
        Date reservationTime = new Date(1_700_001_000_900L);
        MetadataSourceBinding binding = binding(2L);
        binding.setProfileStatus(MetadataRunStatus.QUEUED);
        binding.setProfileLastRunTime(new Date(1_700_001_000_000L));
        binding.setProfileRunReservationToken("generation-1");
        when(bindingDao.queryById(1L)).thenReturn(binding);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "st_ds_42.orders", "st_ds_42")));
        when(openMetadataClient.listIngestionPipelineRuns(anyString(), eq(1))).thenReturn(List.of());
        when(connectorRegistry.require(DbType.DORIS)).thenReturn(connectorAdapter);
        when(connectorAdapter.profilerPipelineRequest(anyString(), anyString(), anyString(), eq("st_ds_42.orders")))
                .thenReturn(JSON.createObjectNode());
        when(connectorAdapter.autoClassificationPipelineRequest(
                anyString(), anyString(), anyString(), eq("st_ds_42.orders"), nullable(String.class)))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("profile-updated", "st_ds_42.st_ds_42_profiler"))
                .thenReturn(new OpenMetadataEntity("sample-updated", "st_ds_42.st_ds_42_auto_classification"));
        doThrow(new MetadataIntegrationException(MetadataErrorCode.OM_PIPELINE_DEPLOY_ERROR, "deploy failed"))
                .when(openMetadataClient).deployIngestionPipeline("profile-updated");
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(2L))).thenReturn(true);

        service().executeExploration(new MetadataPipelineOperationService.ExplorationReservation(
                1L, 42L, "st_ds_42.orders", null, 1L, reservationTime, "generation-1"));

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(2L));
        assertEquals(MetadataRunStatus.FAILED, saved.getValue().getProfileStatus());
        assertEquals(MetadataErrorCode.OM_PIPELINE_DEPLOY_ERROR.name(), saved.getValue().getProfileLastError());
    }

    @Test
    void listRunsReturnsEmptyExplorationHistoryWhenProfilerPipelineIsAbsent() {
        MetadataSourceBinding binding = binding(0L);
        binding.setOmProfilerPipelineId(null);
        binding.setOmProfilerPipelineFqn(null);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(bindingDao.queryByDataSourceId(42L)).thenReturn(binding);

        var runs = service().listRuns(42L, "EXPLORATION", 5);

        assertEquals(List.of(), runs);
        verify(openMetadataClient, never()).listIngestionPipelineRuns(anyString(), anyInt());
    }

    @Test
    void explorationHistoryMergesBothPipelinesAndIdentifiesTheirSource() {
        MetadataSourceBinding binding = binding(0L);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(bindingDao.queryByDataSourceId(42L)).thenReturn(binding);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 5))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "profile-run", "success", 1_700_000_000L, 1_700_000_010L, 1_700_000_020L, 0)));
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_auto_classification", 5))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "sample-run", "success", 1_700_000_001L, 1_700_000_011L, 1_700_000_025L, 0)));

        var runs = service().listRuns(42L, "EXPLORATION", 5);

        assertEquals(2, runs.size());
        assertEquals("sample-run", runs.get(0).getRunId());
        assertEquals("AUTO_CLASSIFICATION", runs.get(0).getPipelineType());
        assertEquals("profile-run", runs.get(1).getRunId());
        assertEquals("PROFILER", runs.get(1).getPipelineType());
    }

    @Test
    void activeSamplePipelineBlocksAnotherExplorationReservation() {
        MetadataSourceBinding binding = binding(0L);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(bindingDao.queryByDataSourceId(42L)).thenReturn(binding);
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "st_ds_42.orders", "st_ds_42")));
        when(openMetadataClient.listIngestionPipelineRuns(anyString(), eq(1))).thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns(
                "st_ds_42.st_ds_42_auto_classification", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "sample-active", "running", 1_700_000_000L, 1_700_000_010L, null, 0)));

        assertThrows(RuntimeException.class, () -> service().triggerExploration(42L, "st_ds_42.orders"));

        verify(bindingDao, never()).reserveRun(eq(1L), eq(0L), eq(false), isNull(), any(), anyString());
    }

    @Test
    void failedSecondTriggerPersistsPartialExplorationFailure() {
        Date reservationTime = new Date(1_700_001_000_000L);
        MetadataSourceBinding binding = binding(2L);
        binding.setProfileStatus(MetadataRunStatus.QUEUED);
        binding.setProfileLastRunTime(reservationTime);
        binding.setProfileRunReservationToken("generation-1");
        when(bindingDao.queryById(1L)).thenReturn(binding);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "st_ds_42.orders", "st_ds_42")));
        when(openMetadataClient.listIngestionPipelineRuns(anyString(), eq(1))).thenReturn(List.of());
        when(connectorRegistry.require(DbType.DORIS)).thenReturn(connectorAdapter);
        when(connectorAdapter.profilerPipelineRequest(anyString(), anyString(), anyString(), eq("st_ds_42.orders")))
                .thenReturn(JSON.createObjectNode());
        when(connectorAdapter.autoClassificationPipelineRequest(
                anyString(), anyString(), anyString(), eq("st_ds_42.orders"), nullable(String.class)))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("profile-updated", "st_ds_42.st_ds_42_profiler"))
                .thenReturn(new OpenMetadataEntity("sample-updated", "st_ds_42.st_ds_42_auto_classification"));
        doAnswer(invocation -> {
            String pipelineId = invocation.getArgument(0);
            if ("sample-updated".equals(pipelineId)) {
                throw new RuntimeException("sample trigger failed");
            }
            return null;
        }).when(openMetadataClient).triggerIngestionPipeline(anyString());
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), anyLong())).thenReturn(true);

        OmReadCache readCache = org.mockito.Mockito.mock(OmReadCache.class);
        MetadataInventoryCache inventoryCache = org.mockito.Mockito.mock(MetadataInventoryCache.class);
        MetadataPipelineOperationService operationService = service();
        operationService.setOmReadCache(readCache);
        operationService.setMetadataInventoryCache(inventoryCache);
        operationService.executeExploration(new MetadataPipelineOperationService.ExplorationReservation(
                1L, 42L, "st_ds_42.orders", null, 1L, reservationTime, "generation-1"));

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao, times(2)).updateIfVersion(saved.capture(), anyLong());
        assertEquals(MetadataRunStatus.FAILED, saved.getAllValues().get(1).getProfileStatus());
        assertEquals(MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR.name(),
                saved.getAllValues().get(1).getProfileLastError());
        verify(readCache).invalidateService("st_ds_42", false, true);
        verify(inventoryCache).invalidateAllSnapshots();
        verify(openMetadataClient).triggerIngestionPipeline("profile-updated");
        verify(openMetadataClient).triggerIngestionPipeline("sample-updated");
    }

    @Test
    void staleExplorationWorkerCannotTriggerPipelinesAfterANewReservationReplacesIt() {
        Date reservationTime = new Date(1_700_001_000_000L);
        MetadataSourceBinding current = binding(3L);
        current.setProfileStatus(MetadataRunStatus.QUEUED);
        current.setProfileLastRunTime(reservationTime);
        current.setProfileRunReservationToken("new-generation");
        when(bindingDao.queryById(1L)).thenReturn(current);

        service().executeExploration(new MetadataPipelineOperationService.ExplorationReservation(
                1L, 42L, "st_ds_42.orders", null, 1L, reservationTime, "old-generation"));

        verify(openMetadataClient, never()).upsertIngestionPipeline(any());
        verify(openMetadataClient, never()).triggerIngestionPipeline(anyString());
        verify(bindingDao, never()).updateIfVersion(any(MetadataSourceBinding.class), anyLong());
    }

    @Test
    void tokenlessCompatibilityReservationCannotStartExternalWork() {
        service().executeExploration(new MetadataPipelineOperationService.ExplorationReservation(
                1L, 42L, "st_ds_42.orders", 1L, new Date(1_700_001_000_000L)));

        verify(bindingDao, never()).queryById(anyLong());
        verify(openMetadataClient, never()).findDatabase(anyString());
        verify(openMetadataClient, never()).upsertIngestionPipeline(any());
    }

    @Test
    void duplicateExplorationReservationDoesNotTriggerPipelinesAgain() {
        Date reservationTime = new Date(1_700_001_000_000L);
        MetadataSourceBinding binding = binding(2L);
        binding.setProfileStatus(MetadataRunStatus.QUEUED);
        binding.setProfileLastRunTime(reservationTime);
        binding.setProfileRunReservationToken("generation-1");
        binding.setProfileRunBaselineCaptured(true);
        when(bindingDao.queryById(1L)).thenReturn(binding);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(openMetadataClient.findDatabase("st_ds_42.orders"))
                .thenReturn(Optional.of(new OpenMetadataDatabase("database-id", "st_ds_42.orders", "st_ds_42")));
        when(openMetadataClient.listIngestionPipelineRuns(anyString(), eq(1))).thenReturn(List.of());
        when(connectorRegistry.require(DbType.DORIS)).thenReturn(connectorAdapter);
        when(connectorAdapter.profilerPipelineRequest(anyString(), anyString(), anyString(), eq("st_ds_42.orders")))
                .thenReturn(JSON.createObjectNode());
        when(connectorAdapter.autoClassificationPipelineRequest(
                anyString(), anyString(), anyString(), eq("st_ds_42.orders"), nullable(String.class)))
                .thenReturn(JSON.createObjectNode());
        when(openMetadataClient.upsertIngestionPipeline(any()))
                .thenReturn(new OpenMetadataEntity("profile-updated", "st_ds_42.st_ds_42_profiler"))
                .thenReturn(new OpenMetadataEntity("sample-updated", "st_ds_42.st_ds_42_auto_classification"));

        service().executeExploration(new MetadataPipelineOperationService.ExplorationReservation(
                1L, 42L, "st_ds_42.orders", null, 1L, reservationTime, "generation-1"));

        verify(openMetadataClient, never()).triggerIngestionPipeline(anyString());
        verify(bindingDao, never()).updateIfVersion(any(MetadataSourceBinding.class), anyLong());
    }

    @Test
    void listRunsPrependsLocalExplorationFailureWhenOpenMetadataHasNoRuns() {
        MetadataSourceBinding binding = binding(0L);
        binding.setProfileStatus(MetadataRunStatus.FAILED);
        binding.setProfileLastError(MetadataErrorCode.OM_SERVICE_SYNC_ERROR.name());
        binding.setProfileLastRunTime(new Date(1_700_001_000_000L));
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(bindingDao.queryByDataSourceId(42L)).thenReturn(binding);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 5)).thenReturn(List.of());

        var runs = service().listRuns(42L, "EXPLORATION", 5);

        assertEquals(1, runs.size());
        assertEquals("local-exploration-failure", runs.get(0).getRunId());
        assertEquals(MetadataRunStatus.FAILED, runs.get(0).getStatus());
        assertEquals(MetadataErrorCode.OM_SERVICE_SYNC_ERROR.name(), runs.get(0).getErrorMessage());
    }

    @Test
    void listRunsSurfacesNeverWithLocalErrorAsFailureLog() {
        MetadataSourceBinding binding = binding(0L);
        binding.setProfileStatus(MetadataRunStatus.NEVER);
        binding.setProfileLastError(MetadataErrorCode.OM_SERVICE_SYNC_ERROR.name());
        binding.setProfileLastRunTime(new Date(1_700_001_000_000L));
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(bindingDao.queryByDataSourceId(42L)).thenReturn(binding);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 5)).thenReturn(List.of());

        var runs = service().listRuns(42L, "EXPLORATION", 5);

        assertEquals(1, runs.size());
        assertEquals(MetadataRunStatus.FAILED, runs.get(0).getStatus());
        assertEquals(MetadataErrorCode.OM_SERVICE_SYNC_ERROR.name(), runs.get(0).getErrorMessage());
    }

    @Test
    void manualScanReopensRetryableErrorBindingsInsteadOfRequiringReady() {
        MetadataSourceBinding binding = binding(0L);
        binding.setSyncStatus(MetadataSyncStatus.ERROR);
        binding.setLastSyncErrorCode(MetadataErrorCode.SOURCE_CONNECTION_ERROR.name());
        binding.setLastSyncError("Kafka metadata extraction requires schemaRegistryUrl");
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(bindingDao.queryByDataSourceId(42L)).thenReturn(binding);
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        assertTrue(service().triggerScan(42L));

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataSyncStatus.PENDING, saved.getValue().getSyncStatus());
        assertNull(saved.getValue().getLastSyncErrorCode());
        verify(openMetadataClient, never()).triggerIngestionPipeline(anyString());
    }

    @Test
    void manualScanRejectsUnsupportedConnectorErrors() {
        MetadataSourceBinding binding = binding(0L);
        binding.setSyncStatus(MetadataSyncStatus.ERROR);
        binding.setLastSyncErrorCode(MetadataErrorCode.CONNECTOR_NOT_SUPPORTED.name());
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(bindingDao.queryByDataSourceId(42L)).thenReturn(binding);

        assertThrows(RuntimeException.class, () -> service().triggerScan(42L));
        verify(openMetadataClient, never()).triggerIngestionPipeline(anyString());
        verify(bindingDao, never()).updateIfVersion(any(MetadataSourceBinding.class), anyLong());
    }

    @Test
    void storageManifestAcceptsBucketManifestJsonAndClearsOnBlank() {
        String manifest = "{\"entries\":[{\"dataPath\":\"orders/**\",\"structureFormat\":\"parquet\"}]}";

        assertEquals(
                "{\"entries\":[{\"dataPath\":\"orders/**\",\"structureFormat\":\"parquet\"}]}",
                MetadataPipelineOperationService.normalizeStorageManifest(manifest));
        assertNull(MetadataPipelineOperationService.normalizeStorageManifest("  "));
        assertNull(MetadataPipelineOperationService.normalizeStorageManifest(null));
    }

    @Test
    void storageManifestRejectsJsonWithoutEntries() {
        assertThrows(
                RuntimeException.class,
                () -> MetadataPipelineOperationService.normalizeStorageManifest("{\"foo\":1}"));
        assertThrows(
                RuntimeException.class,
                () -> MetadataPipelineOperationService.normalizeStorageManifest("not-json"));
        assertThrows(
                RuntimeException.class,
                () -> MetadataPipelineOperationService.normalizeStorageManifest("[1,2]"));
    }

    @Test
    void storageManifestIsRejectedForDataSourcesThatCannotUseIt() {
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(connectorRegistry.find(DbType.DORIS)).thenReturn(Optional.of(connectorAdapter));
        when(connectorAdapter.supportsStorageManifest()).thenReturn(false);

        assertThrows(
                RuntimeException.class,
                () -> service().updateStorageManifest(42L, "{\"entries\":[]}"));
        verify(metadataBindingCommandService, never()).markStorageManifestChanged(anyLong(), any());
    }

    @Test
    void storageManifestIsPersistedForStorageConnectors() {
        MetadataSourceBinding binding = binding(0L);
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(connectorRegistry.find(DbType.DORIS)).thenReturn(Optional.of(connectorAdapter));
        when(connectorAdapter.supportsStorageManifest()).thenReturn(true);

        service().updateStorageManifest(42L, "{\"entries\":[]}");

        verify(metadataBindingCommandService).markStorageManifestChanged(42L, "{\"entries\":[]}");
    }

    private void stubReady(MetadataSourceBinding binding, MetadataSourceBinding reserved) {
        when(dataSourceDao.queryById(42L)).thenReturn(source());
        when(bindingDao.queryByDataSourceId(42L)).thenReturn(binding);
        when(openMetadataClient.listIngestionPipelineRuns(anyString(), eq(1))).thenReturn(List.of());
        when(bindingDao.queryById(1L)).thenReturn(reserved);
    }

    /** Exploration only reaches the profiler pipeline request for relational adapters. */
    private void stubProfilerCapable() {
        when(connectorRegistry.find(DbType.DORIS)).thenReturn(Optional.of(connectorAdapter));
        when(connectorAdapter.supportsProfiler()).thenReturn(true);
    }

    private MetadataPipelineOperationService service() {
        OpenMetadataProperties properties = new OpenMetadataProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:8585/api");
        properties.setToken("test-token");
        return service(properties);
    }

    private MetadataPipelineOperationService service(OpenMetadataProperties properties) {
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
