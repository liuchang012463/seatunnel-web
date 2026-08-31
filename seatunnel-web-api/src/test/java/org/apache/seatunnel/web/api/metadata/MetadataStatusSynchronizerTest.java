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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    void preservesAQueuedExplorationWhenOpenMetadataOnlyReturnsThePreviousRun() {
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
        assertEquals(MetadataRunStatus.QUEUED, saved.getValue().getProfileStatus());
        assertEquals(reservationTime, saved.getValue().getProfileLastRunTime());
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

    private MetadataStatusSynchronizer synchronizer() {
        MetadataStatusProperties properties = new MetadataStatusProperties();
        properties.setBatchSize(50);
        return new MetadataStatusSynchronizer(bindingDao, openMetadataClient, properties, operationService);
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
