package org.apache.seatunnel.web.api.lake.catalog;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Short-lived process-local cache for source reachability proved from Doris.
 *
 * <p>The key includes the source revision, so a datasource credential or
 * endpoint change cannot reuse an old FE/BE observation.  The cache contains
 * only the boolean outcome and timestamp; URLs, credentials and SQL are never
 * retained.</p>
 */
@Component
public final class LakeSourceNetworkProbeCache {

    private static final int MAX_ENTRIES = 512;

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    public Optional<ProbeResult> get(String key, Duration ttl) {
        if (key == null || key.isBlank() || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return Optional.empty();
        }
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        long ttlMillis;
        try {
            ttlMillis = Math.max(1L, ttl.toMillis());
        } catch (ArithmeticException exception) {
            ttlMillis = Long.MAX_VALUE;
        }
        if (System.currentTimeMillis() - entry.checkedAtMillis() >= ttlMillis) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(new ProbeResult(entry.reachable(), entry.checkedAtMillis()));
    }

    public void put(String key, boolean reachable) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(key)) {
            // Evict one arbitrary stale/old entry rather than allowing a
            // caller-controlled stream of source ids to grow memory without
            // bound.  Exact LRU ordering is unnecessary for this short TTL.
            entries.keySet().stream().findFirst().ifPresent(entries::remove);
        }
        entries.put(key, new Entry(reachable, System.currentTimeMillis()));
    }

    public void invalidateAll() {
        entries.clear();
    }

    private record Entry(boolean reachable, long checkedAtMillis) {
    }

    public record ProbeResult(boolean reachable, long checkedAtMillis) {
    }
}
