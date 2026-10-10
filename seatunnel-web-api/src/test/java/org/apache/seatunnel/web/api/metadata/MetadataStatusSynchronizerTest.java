package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPipelineRun;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataRunStatus;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataStatusSynchronizerTest {

    @Mock private MetadataBindingDao bindingDao;
    @Mock private OpenMetadataClient openMetadataClient;
    @Mock private MetadataPipelineOperationService operationService;

    @Test
    void cachesLatestOmRunsAndThenChecksTheVersionDrivenAutomaticScan() {
        MetadataSourceBinding candidate = binding(0L);
        MetadataSourceBinding live = binding(0L);
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "scan-1", "success", 1700000000L, 1700000010L, 1700000020L, 0)));
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "profile-1", "failed", 1700000100L, 1700000110L, 1700000120L, 0)));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        synchronizer().refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.SUCCESS, saved.getValue().getScanStatus());
        assertEquals(MetadataRunStatus.FAILED, saved.getValue().getProfileStatus());
        assertEquals(MetadataErrorCode.PIPELINE_EXECUTION_ERROR.name(), saved.getValue().getProfileLastError());
        verify(operationService).triggerPendingMetadataScan(saved.getValue());
    }

    @Test
    void marksUnknownInsteadOfFalselyFailingWhenOmCannotBeRead() {
        MetadataSourceBinding candidate = binding(0L);
        MetadataSourceBinding live = binding(0L);
        live.setScanStatus(MetadataRunStatus.RUNNING);
        live.setProfileStatus(MetadataRunStatus.SUCCESS);
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        doThrow(new MetadataIntegrationException(MetadataErrorCode.OM_PIPELINE_STATUS_ERROR, "unavailable"))
                .when(openMetadataClient).assertFixedVersion();

        synchronizer().refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.UNKNOWN, saved.getValue().getScanStatus());
        assertEquals(MetadataRunStatus.SUCCESS, saved.getValue().getProfileStatus());
        assertEquals(MetadataErrorCode.OM_PIPELINE_STATUS_ERROR.name(), saved.getValue().getStatusRefreshError());
    }

    @Test
    void keepsQueuedExplorationAfterStatusReadFailureAndRecoversWithinPreparationTimeout() {
        Date reservationTime = new Date(1_700_000_000_000L);
        Date failedReadTime = new Date(reservationTime.getTime() + 61_000L);
        Date recoveryTime = new Date(reservationTime.getTime() + 90_000L);
        MetadataSourceBinding candidate = queuedExploration(0L, reservationTime);
        MetadataSourceBinding live = queuedExploration(0L, reservationTime);
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1))
                .thenThrow(new MetadataIntegrationException(MetadataErrorCode.OM_PIPELINE_STATUS_ERROR, "unavailable"))
                .thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1))
                .thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_auto_classification", 1))
                .thenReturn(List.of());
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(1L))).thenReturn(true);

        MetadataStatusProperties properties = new MetadataStatusProperties();
        properties.setBatchSize(50);
        properties.setTriggerGraceSeconds(60);
        properties.setExplorationPreparationTimeoutSeconds(600);
        MetadataStatusSynchronizer synchronizer =
                new MetadataStatusSynchronizer(bindingDao, openMetadataClient, properties, operationService);

        synchronizer.refreshOne(candidate, failedReadTime);

        assertEquals(MetadataRunStatus.QUEUED, live.getProfileStatus());
        assertEquals(MetadataErrorCode.OM_PIPELINE_STATUS_ERROR.name(), live.getStatusRefreshError());

        synchronizer.refreshOne(live, recoveryTime);

        assertEquals(MetadataRunStatus.QUEUED, live.getProfileStatus());
        assertNull(live.getStatusRefreshError());
        verify(bindingDao).updateIfVersion(any(MetadataSourceBinding.class), eq(0L));
        verify(bindingDao).updateIfVersion(any(MetadataSourceBinding.class), eq(1L));
    }

    @Test
    void reopensAVersionWhenAReservedTriggerNeverAppearsInOpenMetadata() {
        MetadataSourceBinding candidate = binding(0L);
        candidate.setScanStatus(MetadataRunStatus.QUEUED);
        candidate.setScanLastRunTime(new Date(0));
        MetadataSourceBinding live = binding(0L);
        live.setScanStatus(MetadataRunStatus.QUEUED);
        live.setScanLastRunTime(new Date(0));
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1)).thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1)).thenReturn(List.of());
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        MetadataStatusProperties properties = new MetadataStatusProperties();
        properties.setBatchSize(50);
        properties.setTriggerGraceSeconds(0);
        new MetadataStatusSynchronizer(bindingDao, openMetadataClient, properties, operationService).refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.NEVER, saved.getValue().getScanStatus());
        assertEquals(0L, saved.getValue().getMetadataTriggeredVersion());
    }

    @Test
    void reopensAQueuedScanWhenOpenMetadataOnlyReturnsAnOlderRun() {
        Date reservationTime = new Date(1_700_000_100_000L);
        MetadataSourceBinding candidate = binding(0L);
        candidate.setScanStatus(MetadataRunStatus.QUEUED);
        candidate.setScanLastRunTime(reservationTime);
        candidate.setMetadataTriggeredVersion(3L);
        candidate.setSyncedConfigVersion(3L);
        MetadataSourceBinding live = binding(0L);
        live.setScanStatus(MetadataRunStatus.QUEUED);
        live.setScanLastRunTime(reservationTime);
        live.setMetadataTriggeredVersion(3L);
        live.setSyncedConfigVersion(3L);
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "previous-scan", "success", 1_700_000_000L, 1_700_000_100L, 1_700_000_020L, 0)));
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1)).thenReturn(List.of());
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        synchronizer().refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.NEVER, saved.getValue().getScanStatus());
        assertEquals(2L, saved.getValue().getMetadataTriggeredVersion());
        verify(operationService).triggerPendingMetadataScan(saved.getValue());
    }

    @Test
    void placesARunWithoutStartDateInTimeUsingItsExecutionTimestamp() {
        Date reservationTime = new Date(1_700_000_100_000L);
        MetadataSourceBinding candidate = binding(0L);
        candidate.setScanStatus(MetadataRunStatus.QUEUED);
        candidate.setScanLastRunTime(reservationTime);
        candidate.setMetadataTriggeredVersion(3L);
        candidate.setSyncedConfigVersion(3L);
        MetadataSourceBinding live = binding(0L);
        live.setScanStatus(MetadataRunStatus.QUEUED);
        live.setScanLastRunTime(reservationTime);
        live.setMetadataTriggeredVersion(3L);
        live.setSyncedConfigVersion(3L);
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        // OpenMetadata writes startDate and timestamp from the same clock (workflow_status_mixin),
        // so a run that only carries the execution timestamp still belongs to the reservation.
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "scan-1", "success", null, 1_700_000_100L, null, 0)));
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1)).thenReturn(List.of());
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        synchronizer().refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.SUCCESS, saved.getValue().getScanStatus());
        assertEquals(reservationTime, saved.getValue().getScanLastRunTime());
        assertEquals(reservationTime, saved.getValue().getScanLastSuccessTime());
    }

    @Test
    void failsAQueuedExplorationWhenOnlyOlderProfilerRunExistsAfterGracePeriod() {
        Date reservationTime = new Date(1_700_001_000_000L);
        MetadataSourceBinding candidate = binding(0L);
        candidate.setProfileStatus(MetadataRunStatus.QUEUED);
        candidate.setProfileLastRunTime(reservationTime);
        MetadataSourceBinding live = binding(0L);
        live.setProfileStatus(MetadataRunStatus.QUEUED);
        live.setProfileLastRunTime(reservationTime);
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1))
                .thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "previous-profile", "success", 1_700_000_000L, 1_700_000_010L, 1_700_000_020L, 0)));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        synchronizer().refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.FAILED, saved.getValue().getProfileStatus());
        assertEquals(MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR.name(), saved.getValue().getProfileLastError());
        assertEquals(reservationTime, saved.getValue().getProfileLastRunTime());
    }

    @Test
    void keepsAnUncapturedBaselineAlivePastTheRunRegistrationGracePeriod() {
        Date reservationTime = new Date(System.currentTimeMillis() - 61_000L);
        MetadataSourceBinding candidate = binding(0L);
        candidate.setProfileStatus(MetadataRunStatus.QUEUED);
        candidate.setProfileLastRunTime(reservationTime);
        candidate.setProfileRunBaselineCaptured(false);
        MetadataSourceBinding live = binding(0L);
        live.setProfileStatus(MetadataRunStatus.QUEUED);
        live.setProfileLastRunTime(reservationTime);
        live.setProfileRunBaselineCaptured(false);
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1)).thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1)).thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_auto_classification", 1))
                .thenReturn(List.of());
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        synchronizer().refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.QUEUED, saved.getValue().getProfileStatus());
        assertNull(saved.getValue().getProfileLastError());
    }

    @Test
    void reclaimsAnExplorationWorkerThatNeverCapturesItsBaseline() {
        Date reservationTime = new Date(System.currentTimeMillis() - 601_000L);
        MetadataSourceBinding candidate = binding(0L);
        candidate.setProfileStatus(MetadataRunStatus.QUEUED);
        candidate.setProfileLastRunTime(reservationTime);
        candidate.setProfileRunBaselineCaptured(false);
        MetadataSourceBinding live = binding(0L);
        live.setProfileStatus(MetadataRunStatus.QUEUED);
        live.setProfileLastRunTime(reservationTime);
        live.setProfileRunBaselineCaptured(false);
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1)).thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1)).thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_auto_classification", 1))
                .thenReturn(List.of());
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        MetadataStatusProperties properties = new MetadataStatusProperties();
        properties.setBatchSize(50);
        properties.setExplorationPreparationTimeoutSeconds(600L);
        new MetadataStatusSynchronizer(bindingDao, openMetadataClient, properties, operationService).refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.FAILED, saved.getValue().getProfileStatus());
        assertEquals(MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR.name(), saved.getValue().getProfileLastError());
    }

    @Test
    void startsRunRegistrationGraceWhenTheBaselineWasCaptured() {
        Date reservationTime = new Date(System.currentTimeMillis() - 120_000L);
        Date baselineCapturedAt = new Date(System.currentTimeMillis() - 1_000L);
        MetadataSourceBinding candidate = binding(0L);
        candidate.setProfileStatus(MetadataRunStatus.QUEUED);
        candidate.setProfileLastRunTime(reservationTime);
        candidate.setProfileRunBaselineCaptured(true);
        candidate.setProfileRunBaselineCapturedAt(baselineCapturedAt);
        MetadataSourceBinding live = binding(0L);
        live.setProfileStatus(MetadataRunStatus.QUEUED);
        live.setProfileLastRunTime(reservationTime);
        live.setProfileRunBaselineCaptured(true);
        live.setProfileRunBaselineCapturedAt(baselineCapturedAt);
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1)).thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1)).thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_auto_classification", 1))
                .thenReturn(List.of());
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        synchronizer().refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.QUEUED, saved.getValue().getProfileStatus());
        assertNull(saved.getValue().getProfileLastError());
    }

    @Test
    void queuedExplorationWorkerMayOutlastTheConfiguredRunRegistrationGrace() {
        Date reservationTime = new Date(System.currentTimeMillis() - 90_000L);
        MetadataSourceBinding candidate = binding(0L);
        candidate.setProfileStatus(MetadataRunStatus.QUEUED);
        candidate.setProfileLastRunTime(reservationTime);
        candidate.setProfileRunBaselineCaptured(true);
        candidate.setProfileRunBaselineCapturedAt(reservationTime);
        MetadataSourceBinding live = binding(0L);
        live.setProfileStatus(MetadataRunStatus.QUEUED);
        live.setProfileLastRunTime(reservationTime);
        live.setProfileRunBaselineCaptured(true);
        live.setProfileRunBaselineCapturedAt(reservationTime);
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1)).thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "profile-current", "failed", reservationTime.getTime(), reservationTime.getTime(),
                        reservationTime.getTime(), 0)));
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_auto_classification", 1))
                .thenReturn(List.of());
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        MetadataStatusProperties properties = new MetadataStatusProperties();
        properties.setBatchSize(50);
        properties.setTriggerGraceSeconds(60L);
        new MetadataStatusSynchronizer(bindingDao, openMetadataClient, properties, operationService).refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.QUEUED, saved.getValue().getProfileStatus());
        assertNull(saved.getValue().getProfileLastError());
    }

    @Test
    void completesLegacyExplorationFromProfilerWhenBaselineFlagIsNull() {
        Date reservationTime = new Date(1_700_000_000_000L);
        MetadataSourceBinding candidate = binding(0L);
        candidate.setProfileStatus(MetadataRunStatus.QUEUED);
        candidate.setProfileLastRunTime(reservationTime);
        MetadataSourceBinding live = binding(0L);
        live.setProfileStatus(MetadataRunStatus.QUEUED);
        live.setProfileLastRunTime(reservationTime);
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1)).thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "legacy-profile", "success", 1_700_000_000L, 1_700_000_010L, 1_700_000_020L, 0)));
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_auto_classification", 1))
                .thenReturn(List.of());
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        synchronizer().refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.SUCCESS, saved.getValue().getProfileStatus());
        assertNull(saved.getValue().getProfileLastError());
    }

    @Test
    void preservesALocalExplorationFailureWhenOpenMetadataOnlyReturnsAnOlderRun() {
        Date reservationTime = new Date(1_700_001_000_000L);
        MetadataSourceBinding candidate = binding(0L);
        candidate.setProfileStatus(MetadataRunStatus.FAILED);
        candidate.setProfileLastRunTime(reservationTime);
        candidate.setProfileLastError(MetadataErrorCode.OM_PIPELINE_DEPLOY_ERROR.name());
        MetadataSourceBinding live = binding(0L);
        live.setProfileStatus(MetadataRunStatus.FAILED);
        live.setProfileLastRunTime(reservationTime);
        live.setProfileLastError(MetadataErrorCode.OM_PIPELINE_DEPLOY_ERROR.name());
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1))
                .thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "previous-profile", "success", 1_700_000_000L, 1_700_000_010L, 1_700_000_020L, 0)));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        synchronizer().refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.FAILED, saved.getValue().getProfileStatus());
        assertEquals(MetadataErrorCode.OM_PIPELINE_DEPLOY_ERROR.name(), saved.getValue().getProfileLastError());
        assertEquals(reservationTime, saved.getValue().getProfileLastRunTime());
    }

    @Test
    void restoresNeverWithLocalErrorToFailedWhenOpenMetadataHasNoRuns() {
        Date reservationTime = new Date(1_700_001_000_000L);
        MetadataSourceBinding candidate = binding(0L);
        candidate.setProfileStatus(MetadataRunStatus.NEVER);
        candidate.setProfileLastRunTime(reservationTime);
        candidate.setProfileLastError(MetadataErrorCode.OM_SERVICE_SYNC_ERROR.name());
        MetadataSourceBinding live = binding(0L);
        live.setProfileStatus(MetadataRunStatus.NEVER);
        live.setProfileLastRunTime(reservationTime);
        live.setProfileLastError(MetadataErrorCode.OM_SERVICE_SYNC_ERROR.name());
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1)).thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1)).thenReturn(List.of());
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        synchronizer().refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.FAILED, saved.getValue().getProfileStatus());
        assertEquals(MetadataErrorCode.OM_SERVICE_SYNC_ERROR.name(), saved.getValue().getProfileLastError());
    }

    @Test
    void marksQueuedExplorationFailedWhenOpenMetadataNeverRegistersARun() {
        Date reservationTime = new Date(0);
        MetadataSourceBinding candidate = binding(0L);
        candidate.setProfileStatus(MetadataRunStatus.QUEUED);
        candidate.setProfileLastRunTime(reservationTime);
        MetadataSourceBinding live = binding(0L);
        live.setProfileStatus(MetadataRunStatus.QUEUED);
        live.setProfileLastRunTime(reservationTime);
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1)).thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1)).thenReturn(List.of());
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        MetadataStatusProperties properties = new MetadataStatusProperties();
        properties.setBatchSize(50);
        properties.setTriggerGraceSeconds(0);
        new MetadataStatusSynchronizer(bindingDao, openMetadataClient, properties, operationService).refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.FAILED, saved.getValue().getProfileStatus());
        assertEquals(MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR.name(), saved.getValue().getProfileLastError());
    }

    @Test
    void explorationSucceedsOnlyAfterProfilerAndSamplePipelinesSucceed() {
        Date reservationTime = new Date(1_700_000_000_000L);
        MetadataSourceBinding candidate = binding(0L);
        candidate.setProfileStatus(MetadataRunStatus.RUNNING);
        candidate.setProfileLastRunTime(reservationTime);
        candidate.setProfileRunBaselineCaptured(true);
        MetadataSourceBinding live = binding(0L);
        live.setProfileStatus(MetadataRunStatus.RUNNING);
        live.setProfileLastRunTime(reservationTime);
        live.setProfileRunBaselineCaptured(true);
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1)).thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "profile-current", "success", 1_700_000_000L, 1_700_000_010L, 1_700_000_020L, 0)));
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_auto_classification", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "sample-current", "success", 1_700_000_001L, 1_700_000_011L, 1_700_000_025L, 0)));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        MetadataStatusProperties properties = new MetadataStatusProperties();
        properties.setBatchSize(50);
        properties.setTriggerGraceSeconds(0);
        new MetadataStatusSynchronizer(bindingDao, openMetadataClient, properties, operationService).refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.SUCCESS, saved.getValue().getProfileStatus());
        assertNull(saved.getValue().getProfileLastError());
        assertNotNull(saved.getValue().getProfileLastSuccessTime());
    }

    @Test
    void failsExplorationWhenTheSamplePipelineFailsEvenIfProfilerSucceeds() {
        Date reservationTime = new Date(1_700_000_000_000L);
        MetadataSourceBinding candidate = binding(0L);
        candidate.setProfileStatus(MetadataRunStatus.RUNNING);
        candidate.setProfileLastRunTime(reservationTime);
        candidate.setProfileRunBaselineCaptured(true);
        MetadataSourceBinding live = binding(0L);
        live.setProfileStatus(MetadataRunStatus.RUNNING);
        live.setProfileLastRunTime(reservationTime);
        live.setProfileRunBaselineCaptured(true);
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1)).thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "profile-current", "success", 1_700_000_000L, 1_700_000_010L, 1_700_000_020L, 0)));
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_auto_classification", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "sample-current", "failed", 1_700_000_001L, 1_700_000_011L, 1_700_000_025L, 0)));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        MetadataStatusProperties properties = new MetadataStatusProperties();
        properties.setBatchSize(50);
        new MetadataStatusSynchronizer(bindingDao, openMetadataClient, properties, operationService).refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.FAILED, saved.getValue().getProfileStatus());
        assertEquals(MetadataErrorCode.PIPELINE_EXECUTION_ERROR.name(), saved.getValue().getProfileLastError());
    }

    @Test
    void recoversAnUnknownExplorationWhenBothNewPipelineRunsSucceed() {
        Date reservationTime = new Date(1_700_000_000_000L);
        MetadataSourceBinding candidate = binding(0L);
        candidate.setProfileStatus(MetadataRunStatus.UNKNOWN);
        candidate.setProfileLastRunTime(reservationTime);
        candidate.setProfileRunReservationToken("generation-1");
        candidate.setProfileProfilerRunIdBaseline("old-profile");
        candidate.setProfileSampleRunIdBaseline("old-sample");
        candidate.setProfileRunBaselineCaptured(true);
        MetadataSourceBinding live = binding(0L);
        live.setProfileStatus(MetadataRunStatus.UNKNOWN);
        live.setProfileLastRunTime(reservationTime);
        live.setProfileRunReservationToken("generation-1");
        live.setProfileProfilerRunIdBaseline("old-profile");
        live.setProfileSampleRunIdBaseline("old-sample");
        live.setProfileRunBaselineCaptured(true);
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1)).thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1))
                .thenReturn(List.of(
                        new OpenMetadataPipelineRun(
                                "old-profile", "success", 1_700_000_000L, 1_700_000_001L, 1_700_000_002L, 0),
                        new OpenMetadataPipelineRun(
                                "new-profile", "success", 1_700_000_003L, 1_700_000_004L, 1_700_000_005L, 0)));
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_auto_classification", 1))
                .thenReturn(List.of(
                        new OpenMetadataPipelineRun(
                                "old-sample", "success", 1_700_000_000L, 1_700_000_001L, 1_700_000_002L, 0),
                        new OpenMetadataPipelineRun(
                                "new-sample", "success", 1_700_000_003L, 1_700_000_004L, 1_700_000_006L, 0)));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        MetadataStatusSynchronizer synchronizer = synchronizer();
        OmReadCache omReadCache = mock(OmReadCache.class);
        MetadataInventoryCache inventoryCache = mock(MetadataInventoryCache.class);
        synchronizer.setOmReadCache(omReadCache);
        synchronizer.setMetadataInventoryCache(inventoryCache);
        synchronizer.refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.SUCCESS, saved.getValue().getProfileStatus());
        assertNotNull(saved.getValue().getProfileLastSuccessTime());
        verify(omReadCache).invalidateService("st_ds_42", false, true);
        verify(inventoryCache).invalidateAllSnapshots();
    }

    @Test
    void previousSuccessfulRunsDoNotSatisfyANewExplorationReservation() {
        Date reservationTime = new Date(1_700_000_000_000L);
        MetadataSourceBinding candidate = binding(0L);
        candidate.setProfileStatus(MetadataRunStatus.QUEUED);
        candidate.setProfileLastRunTime(reservationTime);
        candidate.setProfileProfilerRunIdBaseline("old-profile");
        candidate.setProfileSampleRunIdBaseline("old-sample");
        candidate.setProfileRunBaselineCaptured(true);
        MetadataSourceBinding live = binding(0L);
        live.setProfileStatus(MetadataRunStatus.QUEUED);
        live.setProfileLastRunTime(reservationTime);
        live.setProfileProfilerRunIdBaseline("old-profile");
        live.setProfileSampleRunIdBaseline("old-sample");
        live.setProfileRunBaselineCaptured(true);
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1)).thenReturn(List.of());
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "old-profile", "success", 1_700_000_000L, 1_700_000_001L, 1_700_000_002L, 0)));
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_auto_classification", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "old-sample", "success", 1_700_000_000L, 1_700_000_001L, 1_700_000_002L, 0)));
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        MetadataStatusProperties properties = new MetadataStatusProperties();
        properties.setBatchSize(50);
        properties.setTriggerGraceSeconds(0);
        new MetadataStatusSynchronizer(bindingDao, openMetadataClient, properties, operationService).refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        assertEquals(MetadataRunStatus.FAILED, saved.getValue().getProfileStatus());
        assertEquals(MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR.name(), saved.getValue().getProfileLastError());
    }

    @Test
    void storageSampleCollectionDoesNotRepeatForAnAlreadySampledScan() {
        MetadataSourceBinding candidate = binding(0L);
        MetadataSourceBinding live = binding(0L);
        live.setSampleDataEnabled(true);
        // The scan success time is recomputed on every refresh and was observed to drift
        // by a second, so the stable run id is the marker.
        live.setStorageSampleScanRunId("scan-1");
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "scan-1", "success", 1700000000000L, 1700000000437L, 1700000000000L, 0)));
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1))
                .thenReturn(List.of());
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        synchronizer().refreshStatuses();

        verify(operationService, never()).triggerStorageSampleCollection(any());
    }

    @Test
    void storageSampleCollectionRunsForANewScanSuccess() {
        MetadataSourceBinding candidate = binding(0L);
        MetadataSourceBinding live = binding(0L);
        live.setSampleDataEnabled(true);
        live.setStorageSampleScanRunId("scan-1");
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "scan-2", "success", 1700000600000L, 1700000600000L, 1700000600000L, 0)));
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1))
                .thenReturn(List.of());
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(1L))).thenReturn(true);
        when(operationService.triggerStorageSampleCollection(any())).thenReturn(true);

        synchronizer().refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        ArgumentCaptor<Long> versions = ArgumentCaptor.forClass(Long.class);
        verify(bindingDao, times(2)).updateIfVersion(saved.capture(), versions.capture());
        // The status write comes first; the marker is recorded only after the agent accepted
        // the trigger, so a failed trigger leaves the scan unsampled for the next refresh.
        assertEquals(List.of(0L, 1L), versions.getAllValues());
        assertEquals("scan-2", saved.getValue().getStorageSampleScanRunId());
        verify(operationService).triggerStorageSampleCollection(saved.getValue());
    }

    @Test
    void aFailedStorageSampleTriggerLeavesTheScanUnsampledForTheNextRefresh() {
        MetadataSourceBinding candidate = binding(0L);
        MetadataSourceBinding live = binding(0L);
        live.setSampleDataEnabled(true);
        live.setStorageSampleScanRunId("scan-1");
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "scan-2", "success", 1700000600000L, 1700000600000L, 1700000600000L, 0)));
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1))
                .thenReturn(List.of());
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);
        when(operationService.triggerStorageSampleCollection(any())).thenReturn(false);

        synchronizer().refreshStatuses();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(bindingDao).updateIfVersion(saved.capture(), eq(0L));
        // scan-2 must not be recorded as sampled, so the next refresh still sees it as due.
        assertEquals("scan-1", saved.getValue().getStorageSampleScanRunId());
    }

    @Test
    void storageSampleCollectionIsSkippedWhenTheOperatorDidNotOptIn() {
        MetadataSourceBinding candidate = binding(0L);
        MetadataSourceBinding live = binding(0L);
        live.setSampleDataEnabled(false);
        when(bindingDao.queryStatusRefreshCandidates(any(Date.class), eq(50))).thenReturn(List.of(candidate));
        when(bindingDao.queryById(1L)).thenReturn(live);
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 1))
                .thenReturn(List.of(new OpenMetadataPipelineRun(
                        "scan-3", "success", 1700000600000L, 1700000600000L, 1700000600000L, 0)));
        when(openMetadataClient.listIngestionPipelineRuns("st_ds_42.st_ds_42_profiler", 1))
                .thenReturn(List.of());
        when(bindingDao.updateIfVersion(any(MetadataSourceBinding.class), eq(0L))).thenReturn(true);

        synchronizer().refreshStatuses();

        verify(operationService, never()).triggerStorageSampleCollection(any());
    }

    private MetadataStatusSynchronizer synchronizer() {
        MetadataStatusProperties properties = new MetadataStatusProperties();
        properties.setBatchSize(50);
        return new MetadataStatusSynchronizer(bindingDao, openMetadataClient, properties, operationService);
    }

    private static MetadataSourceBinding queuedExploration(Long version, Date reservationTime) {
        MetadataSourceBinding binding = binding(version);
        binding.setProfileStatus(MetadataRunStatus.QUEUED);
        binding.setProfileLastRunTime(reservationTime);
        binding.setProfileRunBaselineCaptured(true);
        binding.setProfileRunBaselineCapturedAt(reservationTime);
        return binding;
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
        binding.setOmMetadataPipelineFqn("st_ds_42.st_ds_42_metadata");
        binding.setOmProfilerPipelineFqn("st_ds_42.st_ds_42_profiler");
        binding.setVersion(version);
        return binding;
    }
}
