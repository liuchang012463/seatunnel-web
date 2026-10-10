package org.apache.seatunnel.web.api.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.service.MetadataBindingCommandService;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataRunStatus;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.common.utils.MetadataStableName;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Local metadata-binding primitive. External work is delegated to the reconciler. */
@Slf4j
@Service
public class MetadataBindingCommandServiceImpl implements MetadataBindingCommandService {

    private static final long INITIAL_CONFIG_VERSION = 1L;
    private static final long INITIAL_SYNCED_CONFIG_VERSION = 0L;
    private static final long INITIAL_TRIGGERED_VERSION = 0L;
    private static final long INITIAL_BINDING_VERSION = 0L;

    @Resource
    private MetadataBindingDao metadataBindingDao;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MetadataSourceBinding createForDataSource(Long dataSourceId) {
        validateDataSourceId(dataSourceId);

        MetadataSourceBinding existing = metadataBindingDao.queryByDataSourceId(dataSourceId);
        if (existing != null) {
            return existing;
        }

        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setDataSourceId(dataSourceId);
        binding.setDesiredState(MetadataDesiredState.ACTIVE);
        binding.setSyncStatus(MetadataSyncStatus.PENDING);
        binding.setConfigVersion(INITIAL_CONFIG_VERSION);
        binding.setSyncedConfigVersion(INITIAL_SYNCED_CONFIG_VERSION);
        binding.setMetadataTriggeredVersion(INITIAL_TRIGGERED_VERSION);
        binding.setScanStatus(MetadataRunStatus.NEVER);
        binding.setProfileStatus(MetadataRunStatus.NEVER);
        binding.setRetryCount(0);
        binding.setVersion(INITIAL_BINDING_VERSION);

        // OM actual IDs/FQNs intentionally remain null until the future reconciler adopts them.
        binding.initInsert();
        metadataBindingDao.insert(binding);
        return binding;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MetadataSourceBinding markConfigurationChanged(Long dataSourceId) {
        validateDataSourceId(dataSourceId);

        MetadataSourceBinding binding = metadataBindingDao.queryByDataSourceId(dataSourceId);
        if (binding == null) {
            return createForDataSource(dataSourceId);
        }

        long currentVersion = binding.getConfigVersion() == null ? 0L : binding.getConfigVersion();
        binding.setConfigVersion(currentVersion + 1L);
        binding.setDesiredState(MetadataDesiredState.ACTIVE);
        binding.setSyncStatus(MetadataSyncStatus.PENDING);
        if (binding.getSyncedConfigVersion() == null) {
            binding.setSyncedConfigVersion(INITIAL_SYNCED_CONFIG_VERSION);
        }
        if (binding.getMetadataTriggeredVersion() == null) {
            binding.setMetadataTriggeredVersion(INITIAL_TRIGGERED_VERSION);
        }
        if (binding.getScanStatus() == null) {
            binding.setScanStatus(MetadataRunStatus.NEVER);
        }
        if (binding.getProfileStatus() == null) {
            binding.setProfileStatus(MetadataRunStatus.NEVER);
        }
        if (binding.getRetryCount() == null) {
            binding.setRetryCount(0);
        }
        binding.setVersion((binding.getVersion() == null ? 0L : binding.getVersion()) + 1L);
        binding.initUpdate();
        metadataBindingDao.updateById(binding);
        return binding;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MetadataSourceBinding markSampleDataChanged(Long dataSourceId, boolean enabled) {
        validateDataSourceId(dataSourceId);

        MetadataSourceBinding binding = metadataBindingDao.queryByDataSourceId(dataSourceId);
        if (binding == null) {
            binding = createForDataSource(dataSourceId);
        }
        if (Boolean.valueOf(enabled).equals(binding.getSampleDataEnabled())) {
            return binding;
        }
        binding.setSampleDataEnabled(enabled);
        binding.setConfigVersion((binding.getConfigVersion() == null ? 0L : binding.getConfigVersion()) + 1L);
        binding.setSyncStatus(MetadataSyncStatus.PENDING);
        binding.setVersion((binding.getVersion() == null ? 0L : binding.getVersion()) + 1L);
        binding.initUpdate();
        metadataBindingDao.updateById(binding);
        return binding;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MetadataSourceBinding markStorageManifestChanged(Long dataSourceId, String manifest) {
        validateDataSourceId(dataSourceId);

        MetadataSourceBinding binding = metadataBindingDao.queryByDataSourceId(dataSourceId);
        if (binding == null) {
            binding = createForDataSource(dataSourceId);
        }
        String current = binding.getStorageManifestConfig();
        boolean unchanged = manifest == null ? current == null : manifest.equals(current);
        if (unchanged) {
            return binding;
        }
        binding.setStorageManifestConfig(manifest);
        binding.setConfigVersion((binding.getConfigVersion() == null ? 0L : binding.getConfigVersion()) + 1L);
        binding.setSyncStatus(MetadataSyncStatus.PENDING);
        binding.setVersion((binding.getVersion() == null ? 0L : binding.getVersion()) + 1L);
        binding.initUpdate();
        metadataBindingDao.updateById(binding);
        return binding;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int resetForOpenMetadataInstanceChange() {
        List<MetadataSourceBinding> bindings = metadataBindingDao.queryAll();
        int resetCount = 0;
        for (MetadataSourceBinding binding : bindings) {
            if (binding.getDesiredState() != MetadataDesiredState.ACTIVE
                    && binding.getDesiredState() != MetadataDesiredState.DELETED) {
                continue;
            }

            boolean active = binding.getDesiredState() == MetadataDesiredState.ACTIVE;
            binding.setSyncStatus(active ? MetadataSyncStatus.PENDING : MetadataSyncStatus.DELETING);
            if (active) {
                binding.setConfigVersion((binding.getConfigVersion() == null ? 0L : binding.getConfigVersion()) + 1L);
                binding.setOmServiceFqn(null);
                binding.setOmMetadataPipelineFqn(null);
                binding.setOmProfilerPipelineFqn(null);
            } else {
                binding.setOmServiceFqn(MetadataStableName.serviceFqn(binding.getDataSourceId()));
                binding.setOmMetadataPipelineFqn(MetadataStableName.metadataPipelineFqn(binding.getDataSourceId()));
                binding.setOmProfilerPipelineFqn(MetadataStableName.profilerPipelineFqn(binding.getDataSourceId()));
            }
            // IDs belong to the previous endpoint. Active reconciliation upserts by stable FQN;
            // deleted reconciliation resolves the retained FQN on the new endpoint.
            binding.setOmServiceId(null);
            binding.setOmMetadataPipelineId(null);
            binding.setOmProfilerPipelineId(null);
            binding.setRetryCount(0);
            binding.setNextRetryTime(null);
            binding.setLastSyncErrorCode(null);
            binding.setLastSyncError(null);
            long expectedVersion = binding.getVersion() == null ? 0L : binding.getVersion();
            binding.setVersion(expectedVersion + 1L);
            binding.initUpdate();
            if (!metadataBindingDao.updateIfVersion(binding, expectedVersion)) {
                throw new IllegalStateException(
                        "Could not reset metadata binding for dataSourceId=" + binding.getDataSourceId()
                                + ": the binding changed concurrently");
            }
            resetCount++;
        }
        return resetCount;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MetadataSourceBinding markDeleted(Long dataSourceId) {
        validateDataSourceId(dataSourceId);
        MetadataSourceBinding binding = metadataBindingDao.queryByDataSourceId(dataSourceId);
        if (binding == null) {
            binding = createForDataSource(dataSourceId);
        }

        long currentVersion = binding.getConfigVersion() == null ? 0L : binding.getConfigVersion();
        binding.setConfigVersion(currentVersion + 1L);
        binding.setDesiredState(MetadataDesiredState.DELETED);
        binding.setSyncStatus(MetadataSyncStatus.DELETING);
        binding.setVersion((binding.getVersion() == null ? 0L : binding.getVersion()) + 1L);
        binding.initUpdate();
        metadataBindingDao.updateById(binding);
        return binding;
    }

    private void validateDataSourceId(Long dataSourceId) {
        if (dataSourceId == null || dataSourceId <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "dataSourceId");
        }
    }
}
