package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabase;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabaseSchema;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTable;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTableProfile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Read-through Redis cache for the OpenMetadata list pages and latest-table
 * profiles that the inventory and exploration walks consume.  One snapshot
 * page of tables can cost several megabytes; caching them turns repeated
 * aggregate rebuilds into Redis reads instead of hundreds of HTTP round
 * trips.
 *
 * <p>Invalidation is per OpenMetadata service and O(1): each service owns an
 * epoch counter that is bumped when a metadata scan ({@code structure}) or a
 * profiler run ({@code profile}) completes for it.  Keys carry the epoch, so
 * stale entries simply expire by TTL.</p>
 *
 * <p>Redis outages degrade to direct loads; the walk stays correct, only
 * slower.</p>
 */
@Slf4j
public class OmReadCache {

    private static final String EPOCH_PREFIX = "stweb:om:ep:";
    private static final String PROFILE_EPOCH_PREFIX = "stweb:om:ep:prof:";
    private static final String DB_PAGE_PREFIX = "stweb:om:db:";
    private static final String SCHEMA_PAGE_PREFIX = "stweb:om:sch:";
    private static final String TABLE_PAGE_PREFIX = "stweb:om:tab:";
    private static final String PROFILE_PREFIX = "stweb:om:prof:";
    /** Local reuse window for the per-service epoch counter. */
    private static final long EPOCH_CACHE_MS = 1_000L;

    /** Deserialization-safe envelope for {@link OpenMetadataPage} records. */
    public record PageEnvelope<T>(List<T> data, long total, String after) {
    }

    /** Wraps profiles so an absent profile is also cached (negative caching). */
    record ProfileEnvelope(OpenMetadataTableProfile profile) {
    }

    private final StringRedisMetadataStore store;
    private final Duration pageTtl;
    private final Duration profileTtl;
    private final ConcurrentHashMap<String, long[]> localEpochs = new ConcurrentHashMap<>();

    private OmReadCache(StringRedisMetadataStore store, Duration pageTtl, Duration profileTtl) {
        this.store = store;
        this.pageTtl = pageTtl;
        this.profileTtl = profileTtl;
    }

    /** Pass-through instance used by unit tests and memory-only deployments. */
    public static OmReadCache disabled() {
        return new OmReadCache(null, Duration.ZERO, Duration.ZERO);
    }

    public static OmReadCache redis(StringRedisMetadataStore store, Duration pageTtl, Duration profileTtl) {
        return new OmReadCache(store, pageTtl, profileTtl);
    }

    public OpenMetadataPage<OpenMetadataDatabase> databases(
            String serviceFqn, int limit, String after,
            Supplier<OpenMetadataPage<OpenMetadataDatabase>> loader) {
        return page(DB_PAGE_PREFIX + serviceFqn, limit, after, OpenMetadataDatabase.class, loader);
    }

    public OpenMetadataPage<OpenMetadataDatabaseSchema> schemas(
            String databaseFqn, int limit, String after,
            Supplier<OpenMetadataPage<OpenMetadataDatabaseSchema>> loader) {
        return page(SCHEMA_PAGE_PREFIX + databaseFqn, limit, after, OpenMetadataDatabaseSchema.class, loader);
    }

    public OpenMetadataPage<OpenMetadataTable> tables(
            String schemaFqn, boolean includeColumns, int limit, String after,
            Supplier<OpenMetadataPage<OpenMetadataTable>> loader) {
        return page(TABLE_PAGE_PREFIX + schemaFqn + ":" + (includeColumns ? "cols" : "bare"),
                limit, after, OpenMetadataTable.class, loader);
    }

    public OpenMetadataPage<OpenMetadataTable> tablesByDatabase(
            String databaseFqn, boolean includeColumns, int limit, String after,
            Supplier<OpenMetadataPage<OpenMetadataTable>> loader) {
        return page(TABLE_PAGE_PREFIX + databaseFqn + ":db:" + (includeColumns ? "cols" : "bare"),
                limit, after, OpenMetadataTable.class, loader);
    }

    public OpenMetadataTableProfile latestProfile(
            String tableFqn, Supplier<OpenMetadataTableProfile> loader) {
        if (store == null) {
            return loader.get();
        }
        String epochKey = PROFILE_EPOCH_PREFIX + digest(tableServiceFqn(tableFqn));
        String key = PROFILE_PREFIX + epochOf(epochKey) + ":" + digest(tableFqn);
        String raw = store.getString(key).orElse(null);
        if (raw != null) {
            JavaType type = TypeFactory.defaultInstance().constructType(ProfileEnvelope.class);
            Optional<ProfileEnvelope> envelope = store.readJson(raw, type);
            // The envelope itself is present even when the cached profile is
            // absent, so a negative cache hit must not re-enter the loader.
            if (envelope.isPresent()) {
                return envelope.get().profile();
            }
        }
        OpenMetadataTableProfile value = loader.get();
        store.writeJson(new ProfileEnvelope(value), key, profileTtl);
        return value;
    }

    /** Drops the cached OpenMetadata reads of one service. */
    public void invalidateService(String serviceFqn, boolean structureChanged, boolean profileChanged) {
        if (store == null || serviceFqn == null || serviceFqn.isBlank()) {
            return;
        }
        if (structureChanged) {
            bumpEpoch(EPOCH_PREFIX + digest(serviceFqn));
        }
        if (profileChanged) {
            bumpEpoch(PROFILE_EPOCH_PREFIX + digest(serviceFqn));
        }
    }

    /** INCRs the epoch and seeds the local cache so this process sees it at once. */
    private void bumpEpoch(String epochKey) {
        long next = store.increment(epochKey);
        localEpochs.put(epochKey, new long[]{next, System.currentTimeMillis()});
    }

    private <T> OpenMetadataPage<T> page(
            String baseKey, int limit, String after, Class<T> elementType,
            Supplier<OpenMetadataPage<T>> loader) {
        if (store == null) {
            return loader.get();
        }
        String epochKey = EPOCH_PREFIX + digest(serviceOf(baseKey));
        String key = baseKey + ":l" + limit + ":" + epochOf(epochKey)
                + ":" + (after == null ? "first" : digest(after));
        String raw = store.getString(key).orElse(null);
        if (raw != null) {
            JavaType envelopeType = TypeFactory.defaultInstance().constructParametricType(
                    PageEnvelope.class, elementType);
            Optional<PageEnvelope<T>> envelope = store.readJson(raw, envelopeType);
            if (envelope.isPresent()) {
                PageEnvelope<T> cached = envelope.get();
                return new OpenMetadataPage<>(cached.data(), cached.total(), cached.after());
            }
            // An unreadable cached page must fall back to the loader instead of
            // looking like the end of pagination to the walk.
        }
        OpenMetadataPage<T> value = loader.get();
        if (value != null) {
            store.writeJson(new PageEnvelope<>(value.data(), value.total(), value.after()), key, pageTtl);
        }
        return value;
    }

    /** Epoch read with a one-second local reuse window (see {@link #localEpochs}). */
    private long epochOf(String epochKey) {
        long[] entry = localEpochs.get(epochKey);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry[1] <= EPOCH_CACHE_MS) {
            return entry[0];
        }
        long value = store.current(epochKey);
        localEpochs.put(epochKey, new long[]{value, now});
        return value;
    }

    /**
     * Page base keys are {@code <prefix>:<parentFqn>} for structure pages;
     * table pages append the columns flag.  The service fqn is the first
     * dotted segment of the parent fqn.
     */
    private static String serviceOf(String baseKey) {
        String withoutPrefix = baseKey.substring(baseKey.indexOf(':') + 1);
        int flag = withoutPrefix.indexOf(":cols");
        if (flag >= 0) {
            withoutPrefix = withoutPrefix.substring(0, flag);
        }
        int firstDot = withoutPrefix.indexOf('.');
        return firstDot < 0 ? withoutPrefix : withoutPrefix.substring(0, firstDot);
    }

    /**
     * Profiles are cached per service so a profiler run only drops that
     * service's entries; the table fqn is {@code <service>.<db>.<schema>.<table>}.
     */
    private static String tableServiceFqn(String tableFqn) {
        int firstDot = tableFqn == null ? -1 : tableFqn.indexOf('.');
        return firstDot < 0 ? (tableFqn == null ? "" : tableFqn) : tableFqn.substring(0, firstDot);
    }

    static String digest(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
