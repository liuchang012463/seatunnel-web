package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;

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
        SnapshotEnvelope cached = readSnapshot(key);
        if (cached != null && System.currentTimeMillis() - cached.computedAt() <= softTtlMs) {
            return cached.payload();
        }
        if (cached != null) {
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

    private SnapshotEnvelope readSnapshot(String key) {
        String raw = store.getString(snapshotKey(key)).orElse(null);
        if (raw == null) {
            return null;
        }
        return store.<SnapshotEnvelope>readJson(raw, envelopeType())
                .filter(envelope -> envelope.payload() != null)
                .orElse(null);
    }

    /** Rebuilds an expired-but-present snapshot off the request thread. */
    private void startAsyncRebuild(String key, Supplier<InventorySnapshotPayload> supplier) {
        CompletableFuture<Void> promise = inFlight.putIfAbsent(key, new CompletableFuture<>());
        if (promise != null) {
            return;
        }
        CompletableFuture<Void> mine = inFlight.get(key);
        rebuildExecutor.submit(() -> {
            try {
                storeSnapshot(key, supplier);
                mine.complete(null);
            } catch (RuntimeException | Error error) {
                mine.completeExceptionally(error);
            } finally {
                inFlight.remove(key, mine);
            }
        });
    }

    private InventorySnapshotPayload compute(String key, Supplier<InventorySnapshotPayload> supplier) {
        CompletableFuture<Void> promise = inFlight.putIfAbsent(key, new CompletableFuture<>());
        if (promise != null) {
            try {
                promise.join();
            } catch (CompletionException ignored) {
                // The other build failed; fall through and rebuild here.
            }
            SnapshotEnvelope cached = readSnapshot(key);
            if (cached != null) {
                return cached.payload();
            }
        }
        try {
            return storeSnapshot(key, supplier);
        } finally {
            CompletableFuture<Void> mine = inFlight.get(key);
            if (mine != null) {
                mine.complete(null);
                inFlight.remove(key, mine);
            }
        }
    }

    /** Runs the supplier and stores the result; caller owns the in-flight slot. */
    private InventorySnapshotPayload storeSnapshot(String key, Supplier<InventorySnapshotPayload> supplier) {
        InventorySnapshotPayload value = supplier.get();
        store.writeJson(new SnapshotEnvelope(System.currentTimeMillis(), value),
                snapshotKey(key), Duration.ofMillis(Math.max(staleTtlMs, 1_000L)));
        return value;
    }

    private String snapshotKey(String key) {
        return SNAPSHOT_PREFIX + store.current(EPOCH_KEY) + ":" + digest(key);
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
