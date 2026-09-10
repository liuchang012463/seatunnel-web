package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorRegistry;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataBindingRepairRunnerTest {

    @Mock private DataSourceDao dataSourceDao;
    @Mock private MetadataBindingDao metadataBindingDao;
    @Mock private MetadataConnectorRegistry connectorRegistry;

    @Test
    void reopensSupportedBindingsThatFailedAsConnectorNotSupported() {
        MetadataSourceBinding binding = binding(7L, 70L, MetadataSyncStatus.ERROR);
        binding.setLastSyncErrorCode(MetadataErrorCode.CONNECTOR_NOT_SUPPORTED.name());
        DataSource source = source(70L, DbType.KAFKA);
        when(metadataBindingDao.queryAll()).thenReturn(List.of(binding));
        when(dataSourceDao.queryById(70L)).thenReturn(source);
        when(connectorRegistry.supports(DbType.KAFKA)).thenReturn(true);
        when(metadataBindingDao.updateIfVersion(binding, 3L)).thenReturn(true);

        runner().repairLegacyBindings();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(metadataBindingDao).updateIfVersion(saved.capture(), eq(3L));
        assertEquals(MetadataSyncStatus.PENDING, saved.getValue().getSyncStatus());
        assertNull(saved.getValue().getLastSyncErrorCode());
        assertEquals(0, saved.getValue().getRetryCount());
    }

    @Test
    void freezesUnsupportedBindingsWithoutRetry() {
        MetadataSourceBinding binding = binding(8L, 80L, MetadataSyncStatus.PENDING);
        DataSource source = source(80L, DbType.FTP);
        when(metadataBindingDao.queryAll()).thenReturn(List.of(binding));
        when(dataSourceDao.queryById(80L)).thenReturn(source);
        when(connectorRegistry.supports(DbType.FTP)).thenReturn(false);
        when(metadataBindingDao.updateIfVersion(binding, 3L)).thenReturn(true);

        runner().repairLegacyBindings();

        ArgumentCaptor<MetadataSourceBinding> saved = ArgumentCaptor.forClass(MetadataSourceBinding.class);
        verify(metadataBindingDao).updateIfVersion(saved.capture(), eq(3L));
        assertEquals(MetadataSyncStatus.ERROR, saved.getValue().getSyncStatus());
        assertEquals(MetadataErrorCode.CONNECTOR_NOT_SUPPORTED.name(), saved.getValue().getLastSyncErrorCode());
        assertNull(saved.getValue().getNextRetryTime());
        assertTrue(saved.getValue().getRetryCount() >= 5);
    }

    private MetadataBindingRepairRunner runner() {
        return new MetadataBindingRepairRunner(dataSourceDao, metadataBindingDao, connectorRegistry);
    }

    private static MetadataSourceBinding binding(Long id, Long dataSourceId, MetadataSyncStatus status) {
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setId(id);
        binding.setDataSourceId(dataSourceId);
        binding.setDesiredState(MetadataDesiredState.ACTIVE);
        binding.setSyncStatus(status);
        binding.setVersion(3L);
        binding.setRetryCount(2);
        return binding;
    }

    private static DataSource source(Long id, DbType dbType) {
        DataSource source = new DataSource();
        source.setId(id);
        source.setDbType(dbType);
        return source;
    }
}
