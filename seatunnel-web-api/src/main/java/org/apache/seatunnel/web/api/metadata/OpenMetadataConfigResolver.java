package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.dao.entity.OpenMetadataServerConfig;
import org.apache.seatunnel.web.dao.repository.OpenMetadataServerConfigDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves the effective OpenMetadata connection from the singleton DB row only.
 * An empty / incomplete row means not configured — operators must set it under
 * 运行运维 → 探查引擎管理. Once configured, integration is always enabled.
 */
@Component
public class OpenMetadataConfigResolver {

    private final OpenMetadataServerConfigDao configDao;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    @Autowired
    public OpenMetadataConfigResolver(OpenMetadataServerConfigDao configDao) {
        this.configDao = configDao;
    }

    /** Fixed snapshot used by unit tests and one-shot connect probes. */
    public static OpenMetadataConfigResolver fixed(OpenMetadataRuntimeConfig config) {
        return new OpenMetadataConfigResolver(null) {
            @Override
            public OpenMetadataRuntimeConfig resolve() {
                return config == null
                        ? OpenMetadataRuntimeConfig.notConfigured()
                        : config;
            }

            @Override
            public void invalidate() {
                // no-op
            }
        };
    }

    public static OpenMetadataConfigResolver fixed(OpenMetadataProperties properties) {
        return fixed(OpenMetadataRuntimeConfig.fromProperties(properties));
    }

    public OpenMetadataRuntimeConfig resolve() {
        if (configDao == null) {
            return OpenMetadataRuntimeConfig.notConfigured();
        }
        OpenMetadataServerConfig row = configDao.querySingleton();
        if (row == null) {
            OpenMetadataRuntimeConfig empty = OpenMetadataRuntimeConfig.notConfigured();
            cache.set(new Cached(0L, empty));
            return empty;
        }
        long version = row.getConfigVersion() == null ? 1L : row.getConfigVersion();
        Cached cached = cache.get();
        if (cached != null && cached.configVersion == version) {
            return cached.config;
        }
        OpenMetadataRuntimeConfig resolved = fromRow(row, version);
        cache.set(new Cached(version, resolved));
        return resolved;
    }

    public boolean isEnabled() {
        return resolve().isEnabled();
    }

    public void invalidate() {
        cache.set(null);
    }

    /**
     * Rejects blank, Airflow (:8082), and non-/api OpenMetadata base URLs.
     * Shared by the ops save path and the SDK client boundary.
     */
    public static void validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()
                || baseUrl.contains(":8082")
                || baseUrl.contains("/airflow")) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_CONNECTION_ERROR,
                    "OpenMetadata base URL is not configured as a safe /api endpoint");
        }
        String normalized = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        if (!normalized.endsWith("/api")) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_CONNECTION_ERROR,
                    "OpenMetadata base URL must end in /api");
        }
    }

    private static OpenMetadataRuntimeConfig fromRow(OpenMetadataServerConfig row, long version) {
        return new OpenMetadataRuntimeConfig(
                row.getBaseUrl(),
                row.getToken(),
                row.getConnectTimeoutMs() == null ? 2000 : row.getConnectTimeoutMs(),
                row.getReadTimeoutMs() == null ? 10000 : row.getReadTimeoutMs(),
                row.getExpectedServerVersion(),
                row.getExpectedIngestionPatch(),
                version);
    }

    private record Cached(long configVersion, OpenMetadataRuntimeConfig config) {
    }
}
