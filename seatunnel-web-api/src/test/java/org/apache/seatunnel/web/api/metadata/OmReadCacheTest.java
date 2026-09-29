package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabase;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OmReadCacheTest {

    private StringRedisTemplate template;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        template = mock(StringRedisTemplate.class);
        when(template.opsForValue()).thenReturn(ops);
    }

    private OmReadCache newRedisCache() {
        return OmReadCache.redis(
                new StringRedisMetadataStore(template, mapper), Duration.ofMinutes(10), Duration.ofMinutes(15));
    }

    private static OpenMetadataPage<OpenMetadataDatabase> databasePage(String name) {
        OpenMetadataDatabase database = new OpenMetadataDatabase(
                "id-" + name, "svc." + name, "svc");
        return new OpenMetadataPage<>(List.of(database), 1L, null);
    }

    @Test
    void disabledInstanceAlwaysLoadsDirectly() {
        OmReadCache cache = OmReadCache.disabled();
        AtomicInteger loads = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            OpenMetadataPage<OpenMetadataDatabase> page =
                    cache.databases("svc", 100, null, () -> {
                        loads.incrementAndGet();
                        return databasePage("db" + loads.get());
                    });
            assertEquals("svc.db" + loads.get(), page.data().get(0).fullyQualifiedName());
        }
        assertEquals(3, loads.get());
    }

    @Test
    void cachesPagesAndBypassesLoaderOnHit() {
        AtomicReference<String> stored = new AtomicReference<>();
        when(ops.get(anyString())).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        OmReadCache cache = newRedisCache();
        AtomicInteger loads = new AtomicInteger();

        OpenMetadataPage<OpenMetadataDatabase> first =
                cache.databases("svc", 100, null, () -> {
                    loads.incrementAndGet();
                    return databasePage("db");
                });
        assertEquals(1, loads.get());

        OpenMetadataPage<OpenMetadataDatabase> second =
                cache.databases("svc", 100, null, () -> {
                    loads.incrementAndGet();
                    return databasePage("other");
                });
        assertEquals(1, loads.get());
        assertEquals(first.data().get(0).fullyQualifiedName(), second.data().get(0).fullyQualifiedName());
    }

    @Test
    void unreadableCachedPageFallsBackToLoader() {
        when(ops.get(anyString())).thenReturn("{\"data\":\"not-a-page\",");
        OmReadCache cache = newRedisCache();
        AtomicInteger loads = new AtomicInteger();

        OpenMetadataPage<OpenMetadataDatabase> page =
                cache.databases("svc", 100, null, () -> {
                    loads.incrementAndGet();
                    return databasePage("db");
                });
        assertEquals(1, loads.get());
        assertEquals("svc.db", page.data().get(0).fullyQualifiedName());
    }

    @Test
    void pageCacheKeysSeparateLimitAndColumnVariants() {
        when(ops.get(anyString())).thenReturn(null);
        OmReadCache cache = newRedisCache();
        AtomicInteger loads = new AtomicInteger();

        cache.tables("svc.db.s", true, 1000, null, () -> {
            loads.incrementAndGet();
            return new OpenMetadataPage<>(List.of(new OpenMetadataTable()), 0L, null);
        });
        cache.tables("svc.db.s", false, 1000, null, () -> {
            loads.incrementAndGet();
            return new OpenMetadataPage<>(List.of(), 0L, null);
        });
        cache.tables("svc.db.s", true, 20, null, () -> {
            loads.incrementAndGet();
            return new OpenMetadataPage<>(List.of(new OpenMetadataTable()), 0L, null);
        });
        assertEquals(3, loads.get());

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(ops).set(contains("stweb:om:tab:svc.db.s:cols:l1000"), keys.capture(), any(Duration.class));
        // Three distinct keys were written for the three distinct variants.
        verify(ops, org.mockito.Mockito.times(3)).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void structureInvalidationForcesReloadThroughNewEpoch() {
        when(ops.get(anyString())).thenReturn(null);
        OmReadCache cache = newRedisCache();
        AtomicInteger loads = new AtomicInteger();

        cache.databases("svc", 100, null, () -> {
            loads.incrementAndGet();
            return databasePage("db");
        });
        assertEquals(1, loads.get());

        when(ops.increment("stweb:om:ep:" + OmReadCache.digest("svc"))).thenReturn(1L);
        when(ops.get("stweb:om:ep:" + OmReadCache.digest("svc"))).thenReturn("1");
        cache.invalidateService("svc", true, false);

        cache.databases("svc", 100, null, () -> {
            loads.incrementAndGet();
            return databasePage("db2");
        });
        assertEquals(2, loads.get());
    }

    @Test
    void absentProfilesAreNegativelyCached() {
        AtomicReference<String> stored = new AtomicReference<>();
        when(ops.get(anyString())).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        OmReadCache cache = newRedisCache();
        AtomicInteger loads = new AtomicInteger();

        assertNull(cache.latestProfile("svc.db.s.tbl", () -> {
            loads.incrementAndGet();
            return null;
        }));
        assertEquals(1, loads.get());

        assertNull(cache.latestProfile("svc.db.s.tbl", () -> {
            loads.incrementAndGet();
            return null;
        }));
        assertEquals(1, loads.get());
    }

    @Test
    void profileEpochBumpDropsCachedProfiles() {
        when(ops.get(anyString())).thenReturn(null);
        OmReadCache cache = newRedisCache();
        AtomicInteger loads = new AtomicInteger();

        cache.latestProfile("svc.db.s.tbl", () -> {
            loads.incrementAndGet();
            return null;
        });
        assertEquals(1, loads.get());

        when(ops.increment("stweb:om:ep:prof:" + OmReadCache.digest("svc"))).thenReturn(1L);
        when(ops.get("stweb:om:ep:prof:" + OmReadCache.digest("svc"))).thenReturn("1");
        cache.invalidateService("svc", false, true);

        cache.latestProfile("svc.db.s.tbl", () -> {
            loads.incrementAndGet();
            return null;
        });
        assertEquals(2, loads.get());
    }

    @Test
    void differentServicesDigestToDifferentEpochKeys() {
        assertNotEquals(OmReadCache.digest("svc-a"), OmReadCache.digest("svc-b"));
    }
}
