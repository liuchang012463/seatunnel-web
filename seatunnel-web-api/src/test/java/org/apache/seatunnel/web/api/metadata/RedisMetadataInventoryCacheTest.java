package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.spi.bean.vo.DataInventorySummaryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisMetadataInventoryCacheTest {

    private static final long SOFT_TTL_MS = 60_000L;
    private static final long STALE_TTL_MS = 600_000L;

    private StringRedisTemplate template;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        template = mock(StringRedisTemplate.class);
        when(template.opsForValue()).thenReturn(ops);
    }

    private RedisMetadataInventoryCache newCache() {
        return new RedisMetadataInventoryCache(
                new StringRedisMetadataStore(template, mapper), SOFT_TTL_MS, STALE_TTL_MS);
    }

    private static InventorySnapshotPayload payload(long dataSourceCount) {
        DataInventorySummaryVO summary = new DataInventorySummaryVO();
        summary.setDataSourceCount(dataSourceCount);
        summary.setTableCount(dataSourceCount * 10);
        return new InventorySnapshotPayload(summary, List.of(), List.of(), List.of(), null);
    }

    @Test
    void buildsOnceOnMissAndServesFollowingReadsFromRedis() throws Exception {
        AtomicInteger builds = new AtomicInteger();
        Supplier<InventorySnapshotPayload> supplier = () -> {
            builds.incrementAndGet();
            return payload(7L);
        };
        when(ops.get(anyString())).thenReturn(null);
        RedisMetadataInventoryCache cache = newCache();

        assertEquals(7L, cache.getOrCompute("k", supplier).summary().getDataSourceCount());
        assertEquals(1, builds.get());

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(ops).set(contains("stweb:inv:snap:"), stored.capture(), any(Duration.class));
        when(ops.get(anyString())).thenReturn(stored.getValue());

        assertEquals(7L, cache.getOrCompute("k", supplier).summary().getDataSourceCount());
        assertEquals(1, builds.get());
    }

    @Test
    void servesStaleSnapshotImmediatelyWhileRebuildingInBackground() throws Exception {
        AtomicReference<String> stored = new AtomicReference<>();
        when(ops.get(anyString())).thenAnswer(invocation -> stored.get());
        org.mockito.Mockito.doAnswer(invocation -> {
            stored.set(invocation.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));

        long oldComputedAt = System.currentTimeMillis() - SOFT_TTL_MS - 1_000L;
        RedisMetadataInventoryCache cache = newCache();
        stored.set(mapper.writeValueAsString(
                new RedisMetadataInventoryCache.SnapshotEnvelope(oldComputedAt, 0L, payload(1L))));

        AtomicInteger builds = new AtomicInteger();
        InventorySnapshotPayload served = cache.getOrCompute("k", () -> {
            builds.incrementAndGet();
            return payload(2L);
        });

        // Stale value is returned without waiting for the rebuild.
        assertEquals(1L, served.summary().getDataSourceCount());
        awaitTrue(2_000, () -> builds.get() == 1);
        awaitTrue(2_000, () -> stored.get() != null && stored.get().contains("\"dataSourceCount\":2"));

        // The next read sees the refreshed snapshot.
        assertEquals(2L, cache.getOrCompute("k", () -> payload(3L))
                .summary().getDataSourceCount());
    }

    @Test
    void invalidationServesStaleSnapshotAndRebuildsInBackground() throws Exception {
        AtomicReference<String> stored = new AtomicReference<>();
        when(ops.get(anyString())).thenAnswer(invocation -> stored.get());
        org.mockito.Mockito.doAnswer(invocation -> {
            stored.set(invocation.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.increment("stweb:inv:epoch")).thenReturn(1L);
        RedisMetadataInventoryCache cache = newCache();

        cache.getOrCompute("k", () -> payload(1L));

        // Invalidate: the epoch moves and the stored snapshot is now stale.
        when(ops.get("stweb:inv:epoch")).thenReturn("1");
        cache.invalidateAllSnapshots();

        AtomicInteger builds = new AtomicInteger();
        InventorySnapshotPayload served = cache.getOrCompute("k", () -> {
            builds.incrementAndGet();
            return payload(5L);
        });
        // The pre-invalidation snapshot is served immediately; the rebuild
        // happens off the request thread.
        assertEquals(1L, served.summary().getDataSourceCount());
        assertEquals(0, builds.get());

        awaitTrue(2_000, () -> builds.get() == 1);
        awaitTrue(2_000, () -> stored.get() != null && stored.get().contains("\"dataSourceCount\":5"));

        // The next read sees the refreshed snapshot.
        assertEquals(5L, cache.getOrCompute("k", () -> payload(6L))
                .summary().getDataSourceCount());
        assertEquals(1, builds.get());
    }

    @Test
    void redisOutageDegradesToDirectComputation() {
        when(template.opsForValue()).thenThrow(
                new org.springframework.data.redis.RedisConnectionFailureException("connection refused"));
        RedisMetadataInventoryCache cache = newCache();

        InventorySnapshotPayload payload = cache.getOrCompute("k", () -> payload(9L));
        assertEquals(9L, payload.summary().getDataSourceCount());
    }

    private static void awaitTrue(long timeoutMs, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(condition.getAsBoolean(), "condition not met in time");
    }
}
