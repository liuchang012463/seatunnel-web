package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Redis-backed cache for data-inventory aggregate snapshots.
 *
 * <p>Entries are addressed by an epoch counter so invalidation is O(1):
 * {@link #invalidateAllSnapshots()} only increments {@code stweb:inv:epoch}
 * and old-epoch entries expire by TTL.  Within an entry, freshness is judged
 * by the stored {@code computedAt}: requests served after the soft TTL
 * receive the previous snapshot immediately while a background rebuild
 * refreshes it (stale-while-revalidate), so users never wait for an
 * OpenMetadata walk.</p>
 *
 * <p>Concurrent builds of the same key are deduplicated per process.  With
 * several backend instances the same rebuild may run twice; that wastes one
 * walk but stays consistent because the last writer wins.</p>
 */
@Slf4j
public class RedisMetadataInventoryCache implements MetadataInventoryCache {

    private static final String EPOCH_KEY = "stweb:inv:epoch";
    private static final String SNAPSHOT_PREFIX = "stweb:inv:snap:";
    /** How many earlier epochs a miss may fall back to for a stale snapshot. */
    private static final int EPOCH_LOOKBACK = 5;

    private final StringRedisMetadataStore store;
    private final long softTtlMs;
    private final long staleTtlMs;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService rebuildExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "metadata-inventory-swr");
        thread.setDaemon(true);
        return thread;
    });

    public RedisMetadataInventoryCache(StringRedisMetadataStore store, long softTtlMs, long staleTtlMs) {
        this.store = store;
        this.softTtlMs = softTtlMs;
        this.staleTtlMs = staleTtlMs;
    }

    @Override
    public InventorySnapshotPayload getOrCompute(String key, Supplier<InventorySnapshotPayload> supplier) {
        String digest = digest(key);
        SnapshotEnvelope cached = readSnapshot(currentEpoch(), digest);
        if (cached != null && System.currentTimeMillis() - cached.computedAt() <= softTtlMs) {
            return cached.payload();
        }
        if (cached != null) {
            startAsyncRebuild(key, supplier);
            return cached.payload();
        }
        // The epoch was bumped by an invalidation, so the snapshot for the
        // current epoch is still being built.  Serve the newest snapshot of a
        // previous epoch (it expires by the stale TTL) instead of making the
        // caller wait for the walk; only a genuinely empty cache builds sync.
        SnapshotEnvelope previous = readPreviousSnapshot(digest);
        if (previous != null) {
            startAsyncRebuild(key, supplier);
            return previous.payload();
        }
        return compute(key, supplier);
    }

    @Override
    public void invalidateAllSnapshots() {
        store.increment(EPOCH_KEY);
        inFlight.clear();
    }

    private SnapshotEnvelope readSnapshot(long epoch, String digest) {
        String raw = store.getString(SNAPSHOT_PREFIX + epoch + ":" + digest).orElse(null);
        if (raw == null) {
            return null;
        }
        return store.<SnapshotEnvelope>readJson(raw, envelopeType())
                .filter(envelope -> envelope.payload() != null)
                .orElse(null);
    }

    /** Newest snapshot written under an earlier epoch, or null when too old. */
    private SnapshotEnvelope readPreviousSnapshot(String digest) {
        long epoch = currentEpoch();
        for (int back = 1; back <= EPOCH_LOOKBACK && epoch - back >= 0; back++) {
            SnapshotEnvelope candidate = readSnapshot(epoch - back, digest);
            if (candidate != null
                    && System.currentTimeMillis() - candidate.computedAt() <= staleTtlMs) {
                return candidate;
            }
        }
        return null;
    }

    private long currentEpoch() {
        return store.current(EPOCH_KEY);
    }

    /** Rebuilds an expired-but-present snapshot off the request thread. */
    private void startAsyncRebuild(String key, Supplier<InventorySnapshotPayload> supplier) {
        CompletableFuture<Void> promise = inFlight.putIfAbsent(key, new CompletableFuture<>());
        if (promise != null) {
            return;
        }
        CompletableFuture<Void> mine = inFlight.get(key);
        String digest = digest(key);
        long buildEpoch = currentEpoch();
        rebuildExecutor.submit(() -> {
            try {
                storeSnapshot(buildEpoch, digest, supplier);
                mine.complete(null);
            } catch (RuntimeException | Error error) {
                log.warn("Inventory snapshot background rebuild failed: key={}, type={}",
                        key, error.getClass().getSimpleName());
                mine.completeExceptionally(error);
            } finally {
                inFlight.remove(key, mine);
            }
        });
    }

    private InventorySnapshotPayload compute(String key, Supplier<InventorySnapshotPayload> supplier) {
        String digest = digest(key);
        long buildEpoch = currentEpoch();
        CompletableFuture<Void> promise = inFlight.putIfAbsent(key, new CompletableFuture<>());
        if (promise != null) {
            try {
                promise.join();
            } catch (CompletionException ignored) {
                // The other build failed; fall through and rebuild here.
            }
            SnapshotEnvelope cached = readSnapshot(buildEpoch, digest);
            if (cached != null) {
                return cached.payload();
            }
        }
        try {
            return storeSnapshot(buildEpoch, digest, supplier);
        } finally {
            CompletableFuture<Void> mine = inFlight.get(key);
            if (mine != null) {
                mine.complete(null);
                inFlight.remove(key, mine);
            }
        }
    }

    /**
     * Runs the supplier and stores the result under the epoch captured when
     * the build started, so a build that raced an invalidation cannot present
     * itself as fresh for the new epoch.
     */
    private InventorySnapshotPayload storeSnapshot(
            long epoch, String digest, Supplier<InventorySnapshotPayload> supplier) {
        InventorySnapshotPayload value = supplier.get();
        store.writeJson(new SnapshotEnvelope(System.currentTimeMillis(), value),
                SNAPSHOT_PREFIX + epoch + ":" + digest,
                Duration.ofMillis(Math.max(staleTtlMs, 1_000L)));
        return value;
    }

    private JavaType envelopeType() {
        return TypeFactory.defaultInstance().constructType(SnapshotEnvelope.class);
    }

    static String digest(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /** Stored envelope; freshness is judged by computedAt, expiry by Redis TTL. */
    record SnapshotEnvelope(Long computedAt, InventorySnapshotPayload payload) {
    }
}
