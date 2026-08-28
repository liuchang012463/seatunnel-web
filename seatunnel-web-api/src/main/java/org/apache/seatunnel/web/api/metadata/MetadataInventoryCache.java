package org.apache.seatunnel.web.api.metadata;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Small process-local cache for inventory aggregates.  The cache stores only
 * computed counters/distributions; it is not a metadata mirror.  A short TTL
 * keeps an unavailable OpenMetadata instance from making the dashboard
 * permanently stale while avoiding a new persistence table in the MVP.
 */
@Component
public class MetadataInventoryCache {

    private static final long TTL_MILLIS = Duration.ofMinutes(5).toMillis();

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    public Object get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt() <= System.currentTimeMillis()) {
            entries.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    public void put(String key, Object value) {
        if (key != null && value != null) {
            entries.put(key, new Entry(value, System.currentTimeMillis() + TTL_MILLIS));
        }
    }

    /**
     * Shares a cold-cache build between concurrent callers with the same key.
     * The inventory page requests summary and coverage independently in older
     * clients, so this guard prevents two full OpenMetadata walks at once.
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrCompute(String key, Supplier<T> supplier) {
        Object cached = get(key);
        if (cached != null) {
            return (T) cached;
        }
        CompletableFuture<Object> promise = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(key, promise);
        if (existing != null) {
            try {
                return (T) existing.join();
            } catch (CompletionException error) {
                if (error.getCause() instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw error;
            }
        }
        try {
            T value = supplier.get();
            put(key, value);
            promise.complete(value);
            return value;
        } catch (RuntimeException | Error error) {
            promise.completeExceptionally(error);
            throw error;
        } finally {
            inFlight.remove(key, promise);
        }
    }

    /** Invalidates all aggregates because one source may affect all dimensions. */
    public void invalidateDataSource(Long dataSourceId) {
        entries.clear();
        inFlight.clear();
    }

    public void clear() {
        entries.clear();
        inFlight.clear();
    }

    private record Entry(Object value, long expiresAt) {
    }
}
