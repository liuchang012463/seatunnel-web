package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataRunStatus;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
        assertEquals(MetadataSyncStatus.PENDING, changed.getSyncStatus());
        assertEquals(5L, changed.getConfigVersion());
        assertEquals(4L, changed.getVersion());
        verify(metadataBindingDao).updateById(binding);
    }
}
