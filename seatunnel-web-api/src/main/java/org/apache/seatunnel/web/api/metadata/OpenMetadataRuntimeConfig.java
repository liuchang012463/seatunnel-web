package org.apache.seatunnel.web.api.metadata;

/**
 * Immutable effective OpenMetadata connection snapshot.
 * Sourced only from {@code t_seatunnel_web_openmetadata_config}; never logs token.
 * A configured connection is always treated as enabled.
 */
public final class OpenMetadataRuntimeConfig {

    public static final String DEFAULT_SERVER_VERSION = "1.12.10";
    public static final String DEFAULT_INGESTION_PATCH = "1.12.10.0";

    private final String baseUrl;
    private final String token;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String expectedServerVersion;
    private final String expectedIngestionPatch;
    private final long configVersion;

    public OpenMetadataRuntimeConfig(
            String baseUrl,
            String token,
            int connectTimeoutMs,
            int readTimeoutMs,
            String expectedServerVersion,
            String expectedIngestionPatch,
            long configVersion) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.expectedServerVersion =
                expectedServerVersion == null || expectedServerVersion.isBlank()
                        ? DEFAULT_SERVER_VERSION
                        : expectedServerVersion;
        this.expectedIngestionPatch =
                expectedIngestionPatch == null || expectedIngestionPatch.isBlank()
                        ? DEFAULT_INGESTION_PATCH
                        : expectedIngestionPatch;
        this.configVersion = configVersion;
    }

    /** Test helper: map legacy property objects into a fixed runtime snapshot. */
    public static OpenMetadataRuntimeConfig fromProperties(OpenMetadataProperties properties) {
        if (properties == null) {
            return notConfigured();
        }
        return new OpenMetadataRuntimeConfig(
                properties.getBaseUrl(),
                properties.getToken(),
                properties.getConnectTimeoutMs(),
                properties.getReadTimeoutMs(),
                properties.getExpectedServerVersion(),
                properties.getExpectedIngestionPatch(),
                0L);
    }

    public static OpenMetadataRuntimeConfig notConfigured() {
        return new OpenMetadataRuntimeConfig(
                null,
                null,
                2000,
                10000,
                DEFAULT_SERVER_VERSION,
                DEFAULT_INGESTION_PATCH,
                0L);
    }

    /** @deprecated use {@link #notConfigured()} */
    @Deprecated
    public static OpenMetadataRuntimeConfig disabledPlaceholder() {
        return notConfigured();
    }

    /** True when base URL and token are present — OM integration is always-on once configured. */
    public boolean isEnabled() {
        return isConfigured();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getToken() {
        return token;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public String getExpectedServerVersion() {
        return expectedServerVersion;
    }

    public String getExpectedIngestionPatch() {
        return expectedIngestionPatch;
    }

    public long getConfigVersion() {
        return configVersion;
    }

    public boolean isConfigured() {
        return baseUrl != null
                && !baseUrl.isBlank()
                && token != null
                && !token.isBlank();
    }
}
