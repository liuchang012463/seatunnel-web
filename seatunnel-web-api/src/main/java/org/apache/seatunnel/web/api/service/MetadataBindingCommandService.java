package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;

/**
 * Local-only binding commands used by the DataSource transaction boundary.
 * This service intentionally has no OpenMetadata or Airflow dependency.
 */
public interface MetadataBindingCommandService {

    MetadataSourceBinding createForDataSource(Long dataSourceId);

    MetadataSourceBinding markConfigurationChanged(Long dataSourceId);

    /**
     * Requeues local bindings after the configured OpenMetadata endpoint changes.
     * Active bindings are rebuilt by the reconciler; deleted bindings keep their
     * tombstone state and are cleaned by FQN against the new endpoint.
     */
    int resetForOpenMetadataInstanceChange();

    /**
     * Records that a local data source was removed. The binding is deliberately
     * retained so the later reconciler can remove the corresponding OM assets.
     */
    MetadataSourceBinding markDeleted(Long dataSourceId);
}
