package org.apache.seatunnel.web.api.metadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorRegistry;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * One-shot repair after unstructured OpenMetadata adapters land.
 *
 * <ul>
 *   <li>Bindings whose data-source type is now supported and previously failed
 *       with {@code CONNECTOR_NOT_SUPPORTED} are reopened as PENDING.</li>
 *   <li>Bindings whose type still has no adapter (for example FTP) are frozen
 *       as a terminal unsupported ERROR so they stop retrying.</li>
 * </ul>
 */
@Slf4j
@Component
public class MetadataBindingRepairRunner {

    private final DataSourceDao dataSourceDao;
    private final MetadataBindingDao metadataBindingDao;
    private final MetadataConnectorRegistry connectorRegistry;

    public MetadataBindingRepairRunner(
            DataSourceDao dataSourceDao,
            MetadataBindingDao metadataBindingDao,
            MetadataConnectorRegistry connectorRegistry) {
        this.dataSourceDao = dataSourceDao;
        this.metadataBindingDao = metadataBindingDao;
        this.connectorRegistry = connectorRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void repairLegacyBindings() {
        int reopened = 0;
        int unsupported = 0;
        for (MetadataSourceBinding binding : metadataBindingDao.queryAll()) {
            if (binding == null || binding.getId() == null || binding.getDataSourceId() == null) {
                continue;
            }
            if (binding.getDesiredState() != MetadataDesiredState.ACTIVE) {
                continue;
            }
            DataSource dataSource = dataSourceDao.queryById(binding.getDataSourceId());
            if (dataSource == null || dataSource.getDbType() == null) {
                continue;
            }
            try {
                if (connectorRegistry.supports(dataSource.getDbType())) {
                    if (reopenSupported(binding)) {
                        reopened++;
                    }
                } else if (freezeUnsupported(binding)) {
                    unsupported++;
                }
            } catch (Exception error) {
                log.warn("Metadata binding repair skipped dataSourceId={}, type={}",
                        binding.getDataSourceId(), error.getClass().getSimpleName());
            }
        }
        if (reopened > 0 || unsupported > 0) {
            log.info("Metadata binding repair reopened={}, unsupported={}", reopened, unsupported);
        }
    }

    private boolean reopenSupported(MetadataSourceBinding binding) {
        if (binding.getSyncStatus() != MetadataSyncStatus.ERROR) {
            return false;
        }
        String errorCode = binding.getLastSyncErrorCode();
        boolean connectorGap = MetadataErrorCode.CONNECTOR_NOT_SUPPORTED.name().equals(errorCode);
        // Legacy Kafka gate failed with SOURCE_CONNECTION_ERROR before schema registry became optional.
        boolean legacySchemaRegistryGate = MetadataErrorCode.SOURCE_CONNECTION_ERROR.name().equals(errorCode)
                && binding.getLastSyncError() != null
                && binding.getLastSyncError().contains("schemaRegistryUrl");
        if (!connectorGap && !legacySchemaRegistryGate) {
            return false;
        }
        long version = binding.getVersion() == null ? 0L : binding.getVersion();
        binding.setSyncStatus(MetadataSyncStatus.PENDING);
        binding.setRetryCount(0);
        binding.setNextRetryTime(new java.util.Date());
        binding.setLastSyncErrorCode(null);
        binding.setLastSyncError(null);
        binding.setVersion(version + 1L);
        binding.initUpdate();
        return metadataBindingDao.updateIfVersion(binding, version);
    }

    private boolean freezeUnsupported(MetadataSourceBinding binding) {
        if (binding.getSyncStatus() == MetadataSyncStatus.ERROR
                && MetadataErrorCode.CONNECTOR_NOT_SUPPORTED.name().equals(binding.getLastSyncErrorCode())
                && binding.getNextRetryTime() == null) {
            return false;
        }
        if (binding.getSyncStatus() == MetadataSyncStatus.READY
                || binding.getSyncStatus() == MetadataSyncStatus.DELETING
                || binding.getSyncStatus() == MetadataSyncStatus.SYNCING) {
            return false;
        }
        long version = binding.getVersion() == null ? 0L : binding.getVersion();
        binding.setSyncStatus(MetadataSyncStatus.ERROR);
        binding.setLastSyncErrorCode(MetadataErrorCode.CONNECTOR_NOT_SUPPORTED.name());
        binding.setLastSyncError("OpenMetadata connector is not enabled for this data source type.");
        binding.setNextRetryTime(null);
        binding.setRetryCount(Math.max(binding.getRetryCount() == null ? 0 : binding.getRetryCount(), 5));
        binding.setVersion(version + 1L);
        binding.initUpdate();
        return metadataBindingDao.updateIfVersion(binding, version);
    }
}
