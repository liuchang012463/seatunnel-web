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
 * <p>Each snapshot lives under one key per filter digest, stamped with the
 * invalidation epoch it was built for; invalidation is O(1) (increment of
 * {@code stweb:inv:epoch}).  A snapshot is "fresh" while it is both current
 * for the epoch and younger than the soft TTL.  Anything younger than the
 * stale TTL — soft-expired or orphaned by later epoch bumps — is served
 * immediately while a background rebuild refreshes it, so users never wait
 * for an OpenMetadata walk; only a genuinely empty cache builds synchronously.</p>
 *
 * <p>Concurrent builds of the same key are deduplicated per process.  With
 * several backend instances the same rebuild may run twice; that wastes one
 * walk but stays consistent because the last writer wins.</p>
 */
@Slf4j
public class RedisMetadataInventoryCache implements MetadataInventoryCache {

    private static final String EPOCH_KEY = "stweb:inv:epoch";
    private static final String SNAPSHOT_PREFIX = "stweb:inv:snap:";

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
        long epoch = currentEpoch();
        SnapshotEnvelope cached = readEnvelope(digest);
        if (cached != null && isCurrent(cached, epoch)
                && System.currentTimeMillis() - cached.computedAt() <= softTtlMs) {
            return cached.payload();
        }
        if (cached != null && withinStaleWindow(cached)) {
            // Either soft-expired, or invalidated after later epoch bumps: the
            // stored snapshot is still the freshest data available, so return
            // it immediately and refresh off the request thread.
            startAsyncRebuild(key, supplier);
            return cached.payload();
        }
        return compute(key, supplier);
    }

    @Override
    public void invalidateAllSnapshots() {
        store.increment(EPOCH_KEY);
        inFlight.clear();
    }

    private boolean isCurrent(SnapshotEnvelope cached, long epoch) {
        return cached.epoch() != null && cached.epoch() == epoch;
    }

    private boolean withinStaleWindow(SnapshotEnvelope cached) {
        return cached.computedAt() != null
                && System.currentTimeMillis() - cached.computedAt() <= staleTtlMs;
    }

    private SnapshotEnvelope readEnvelope(String digest) {
        String raw = store.getString(SNAPSHOT_PREFIX + digest).orElse(null);
        if (raw == null) {
            return null;
        }
        return store.<SnapshotEnvelope>readJson(raw, envelopeType())
                .filter(envelope -> envelope.payload() != null)
                .orElse(null);
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
            SnapshotEnvelope cached = readEnvelope(digest);
            if (cached != null && isCurrent(cached, buildEpoch)) {
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
     * Runs the supplier and stores the result stamped with the epoch captured
     * when the build started, so a build that raced an invalidation cannot
     * present itself as fresh for the new epoch.
     */
    private InventorySnapshotPayload storeSnapshot(
            long epoch, String digest, Supplier<InventorySnapshotPayload> supplier) {
        InventorySnapshotPayload value = supplier.get();
        store.writeJson(new SnapshotEnvelope(System.currentTimeMillis(), epoch, value),
                SNAPSHOT_PREFIX + digest,
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

    /**
     * Stored envelope; freshness is judged by computedAt, and epoch marks the
     * invalidation generation the snapshot was built for.
     */
    record SnapshotEnvelope(Long computedAt, Long epoch, InventorySnapshotPayload payload) {
    }
}
