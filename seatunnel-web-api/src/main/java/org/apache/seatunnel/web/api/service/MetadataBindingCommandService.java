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
     * Records the operator's OpenMetadata sample-data decision for one data source and
     * requeues the binding so the reconciler re-upserts the service and metadata
     * pipeline with the new value.
     */
    MetadataSourceBinding markSampleDataChanged(Long dataSourceId, boolean enabled);

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
