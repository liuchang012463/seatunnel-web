package org.apache.seatunnel.web.api.metadata;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Process-local cache for inventory aggregates.  Used by unit tests and by
 * deployments that have not enabled the Redis cache; the production default
 * is {@link RedisMetadataInventoryCache}.
 */
public class LocalMetadataInventoryCache implements MetadataInventoryCache {

    private static final long TTL_MILLIS = 300_000L;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<InventorySnapshotPayload>> inFlight = new ConcurrentHashMap<>();

    /**
     * Shares a cold-cache build between concurrent callers with the same key.
     * The inventory page requests summary and coverage independently in older
     * clients, so this guard prevents two full OpenMetadata walks at once.
     */
    @Override
    public InventorySnapshotPayload getOrCompute(String key, Supplier<InventorySnapshotPayload> supplier) {
        Entry entry = entries.get(key);
        if (entry != null && entry.expiresAt() > System.currentTimeMillis()) {
            return entry.value();
        }
        entries.remove(key);
        CompletableFuture<InventorySnapshotPayload> promise = new CompletableFuture<>();
        CompletableFuture<InventorySnapshotPayload> existing = inFlight.putIfAbsent(key, promise);
        if (existing != null) {
            return join(existing);
        }
        try {
            InventorySnapshotPayload value = supplier.get();
            entries.put(key, new Entry(value, System.currentTimeMillis() + TTL_MILLIS));
            promise.complete(value);
            return value;
        } catch (RuntimeException | Error error) {
            promise.completeExceptionally(error);
            throw error;
        } finally {
            inFlight.remove(key, promise);
        }
    }

    private static InventorySnapshotPayload join(CompletableFuture<InventorySnapshotPayload> future) {
        try {
            return future.join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw error;
        }
    }

    @Override
    public void invalidateAllSnapshots() {
        entries.clear();
        inFlight.clear();
    }

    private record Entry(InventorySnapshotPayload value, long expiresAt) {
    }
}
