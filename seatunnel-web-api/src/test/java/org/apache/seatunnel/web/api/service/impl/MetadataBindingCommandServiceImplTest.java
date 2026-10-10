package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataRunStatus;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataBindingCommandServiceImplTest {

    @Mock
    private MetadataBindingDao metadataBindingDao;

    @InjectMocks
    private MetadataBindingCommandServiceImpl service;

    @Test
    void createsPendingBindingWithoutPretendingToKnowOmActualState() {
        when(metadataBindingDao.queryByDataSourceId(1024L)).thenReturn(null);

        MetadataSourceBinding binding = service.createForDataSource(1024L);

        assertEquals(1024L, binding.getDataSourceId());
        assertEquals(MetadataDesiredState.ACTIVE, binding.getDesiredState());
        assertEquals(MetadataSyncStatus.PENDING, binding.getSyncStatus());
        assertEquals(1L, binding.getConfigVersion());
        assertEquals(0L, binding.getSyncedConfigVersion());
        assertEquals(0L, binding.getMetadataTriggeredVersion());
        assertEquals(MetadataRunStatus.NEVER, binding.getScanStatus());
        assertEquals(MetadataRunStatus.NEVER, binding.getProfileStatus());
        assertNull(binding.getOmServiceId());
        assertNull(binding.getOmServiceFqn());
        assertNull(binding.getOmMetadataPipelineFqn());
        assertNull(binding.getOmProfilerPipelineFqn());
        verify(metadataBindingDao).insert(binding);
    }

    @Test
    void incrementsConfigurationVersionAndReturnsToPending() {
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setDataSourceId(1024L);
        binding.setDesiredState(MetadataDesiredState.ACTIVE);
        binding.setSyncStatus(MetadataSyncStatus.READY);
        binding.setConfigVersion(4L);
        binding.setSyncedConfigVersion(4L);
        binding.setMetadataTriggeredVersion(4L);
        binding.setScanStatus(MetadataRunStatus.SUCCESS);
        binding.setProfileStatus(MetadataRunStatus.SUCCESS);
        binding.setRetryCount(0);
        binding.setVersion(3L);
        when(metadataBindingDao.queryByDataSourceId(eq(1024L))).thenReturn(binding);

        MetadataSourceBinding changed = service.markConfigurationChanged(1024L);

        assertEquals(5L, changed.getConfigVersion());
        assertEquals(MetadataSyncStatus.PENDING, changed.getSyncStatus());
        assertEquals(4L, changed.getSyncedConfigVersion());
        assertEquals(4L, changed.getMetadataTriggeredVersion());
        assertEquals(4L, changed.getVersion());
        verify(metadataBindingDao).updateById(binding);
    }

    @Test
    void retainsBindingAndMarksItDeletedForFutureReconciliation() {
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setDataSourceId(1024L);
        binding.setDesiredState(MetadataDesiredState.ACTIVE);
        binding.setSyncStatus(MetadataSyncStatus.READY);
        binding.setConfigVersion(4L);
        binding.setVersion(3L);
        when(metadataBindingDao.queryByDataSourceId(eq(1024L))).thenReturn(binding);

        MetadataSourceBinding changed = service.markDeleted(1024L);

        assertEquals(MetadataDesiredState.DELETED, changed.getDesiredState());
        assertEquals(MetadataSyncStatus.DELETING, changed.getSyncStatus());
        assertEquals(5L, changed.getConfigVersion());
        assertEquals(4L, changed.getVersion());
        verify(metadataBindingDao).updateById(binding);
    }

    @Test
    void resetsActiveBindingsAndKeepsDeletedBindingsAsFqnCleanupTombstones() {
        MetadataSourceBinding active = new MetadataSourceBinding();
        active.setId(11L);
        active.setDataSourceId(1024L);
        active.setDesiredState(MetadataDesiredState.ACTIVE);
        active.setSyncStatus(MetadataSyncStatus.READY);
        active.setConfigVersion(4L);
        active.setVersion(8L);
        active.setOmServiceId("old-service-id");
        active.setOmServiceFqn("old-service-fqn");
        active.setOmMetadataPipelineId("old-metadata-pipeline-id");
        active.setOmMetadataPipelineFqn("old-metadata-pipeline-fqn");
        active.setOmProfilerPipelineId("old-profiler-pipeline-id");
        active.setOmProfilerPipelineFqn("old-profiler-pipeline-fqn");

        MetadataSourceBinding deleted = new MetadataSourceBinding();
        deleted.setId(12L);
        deleted.setDataSourceId(1025L);
        deleted.setDesiredState(MetadataDesiredState.DELETED);
        deleted.setSyncStatus(MetadataSyncStatus.DELETING);
        deleted.setConfigVersion(6L);
        deleted.setVersion(9L);
        deleted.setOmServiceId("old-deleted-service-id");
        deleted.setOmServiceFqn("retained-service-fqn");
        deleted.setOmMetadataPipelineId("old-deleted-pipeline-id");
        deleted.setOmMetadataPipelineFqn("retained-pipeline-fqn");

        when(metadataBindingDao.queryAll()).thenReturn(List.of(active, deleted));
        when(metadataBindingDao.resetForInstanceChange(any(MetadataSourceBinding.class), anyLong()))
                .thenReturn(true);

        int resetCount = service.resetForOpenMetadataInstanceChange();

        assertEquals(2, resetCount);
        assertEquals(MetadataSyncStatus.PENDING, active.getSyncStatus());
        assertEquals(5L, active.getConfigVersion());
        assertEquals(9L, active.getVersion());
        assertNull(active.getOmServiceId());
        assertNull(active.getOmServiceFqn());
        assertNull(active.getOmMetadataPipelineId());
        assertNull(active.getOmMetadataPipelineFqn());
        assertNull(active.getOmProfilerPipelineId());
        assertNull(active.getOmProfilerPipelineFqn());

        assertEquals(MetadataDesiredState.DELETED, deleted.getDesiredState());
        assertEquals(MetadataSyncStatus.DELETING, deleted.getSyncStatus());
        assertEquals(10L, deleted.getVersion());
        assertNull(deleted.getOmServiceId());
        assertNull(deleted.getOmMetadataPipelineId());
        assertEquals("st_ds_1025", deleted.getOmServiceFqn());
        assertEquals("st_ds_1025.st_ds_1025_metadata", deleted.getOmMetadataPipelineFqn());
        // Each reset is written under the version read from the row, like the rest of the subsystem.
        ArgumentCaptor<Long> expectedVersions = ArgumentCaptor.forClass(Long.class);
        verify(metadataBindingDao, times(2))
                .resetForInstanceChange(any(MetadataSourceBinding.class), expectedVersions.capture());
        assertEquals(List.of(8L, 9L), expectedVersions.getAllValues());
    }

    @Test
    void failsTheResetWhenABindingChangedConcurrently() {
        MetadataSourceBinding active = new MetadataSourceBinding();
        active.setId(11L);
        active.setDataSourceId(1024L);
        active.setDesiredState(MetadataDesiredState.ACTIVE);
        active.setSyncStatus(MetadataSyncStatus.READY);
        active.setConfigVersion(4L);
        active.setVersion(8L);
        when(metadataBindingDao.queryAll()).thenReturn(List.of(active));
        when(metadataBindingDao.resetForInstanceChange(any(MetadataSourceBinding.class), anyLong()))
                .thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.resetForOpenMetadataInstanceChange());
    }
}
