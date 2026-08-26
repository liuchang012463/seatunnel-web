package org.apache.seatunnel.web.api.metadata;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Invalidates all aggregates because one source may affect all dimensions. */
    public void invalidateDataSource(Long dataSourceId) {
        entries.clear();
    }

    public void clear() {
        entries.clear();
    }

    private record Entry(Object value, long expiresAt) {
    }
}
