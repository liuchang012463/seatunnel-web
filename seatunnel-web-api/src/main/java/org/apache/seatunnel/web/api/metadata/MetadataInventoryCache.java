package org.apache.seatunnel.web.api.metadata;

import java.util.function.Supplier;

/**
 * Cache for data-inventory aggregate snapshots keyed by normalized filter.
 * Implementations must be safe for concurrent callers and must deduplicate
 * concurrent builds of the same key.
 */
public interface MetadataInventoryCache {

    InventorySnapshotPayload getOrCompute(String key, Supplier<InventorySnapshotPayload> supplier);

    /**
     * Drops every aggregate regardless of key: one source change can affect
     * all filter dimensions.  Redis-backed implementations do this in O(1).
     */
    void invalidateAllSnapshots();
}
