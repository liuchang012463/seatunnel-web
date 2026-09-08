package org.apache.seatunnel.web.api.metadata;

/**
 * Immutable effective OpenMetadata connection snapshot.
 * Sourced from {@code t_seatunnel_web_openmetadata_config} after seed; never logs token.
 */
public final class OpenMetadataRuntimeConfig {

    public static final String DEFAULT_SERVER_VERSION = "1.12.10";
    public static final String DEFAULT_INGESTION_PATCH = "1.12.10.0";

    private final boolean enabled;
    private final String baseUrl;
    private final String token;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String expectedServerVersion;
    private final String expectedIngestionPatch;
    private final String kingbaseTunnelHost;
    private final int kingbaseTunnelPort;
    private final long configVersion;

    public OpenMetadataRuntimeConfig(
            boolean enabled,
            String baseUrl,
            String token,
            int connectTimeoutMs,
            int readTimeoutMs,
            String expectedServerVersion,
            String expectedIngestionPatch,
            String kingbaseTunnelHost,
            int kingbaseTunnelPort,
            long configVersion) {
        this.enabled = enabled;
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
        this.kingbaseTunnelHost = kingbaseTunnelHost;
        this.kingbaseTunnelPort = kingbaseTunnelPort;
        this.configVersion = configVersion;
    }

    public static OpenMetadataRuntimeConfig fromProperties(OpenMetadataProperties properties) {
        if (properties == null) {
            return disabledPlaceholder();
        }
        return new OpenMetadataRuntimeConfig(
                properties.isEnabled(),
                properties.getBaseUrl(),
                properties.getToken(),
                properties.getConnectTimeoutMs(),
                properties.getReadTimeoutMs(),
                properties.getExpectedServerVersion(),
                properties.getExpectedIngestionPatch(),
                properties.getKingbaseTunnelHost(),
                properties.getKingbaseTunnelPort(),
                0L);
    }

    public static OpenMetadataRuntimeConfig disabledPlaceholder() {
        return new OpenMetadataRuntimeConfig(
                false,
                "http://127.0.0.1:8585/api",
                null,
                2000,
                10000,
                DEFAULT_SERVER_VERSION,
                DEFAULT_INGESTION_PATCH,
                null,
                0,
                0L);
    }

    public boolean isEnabled() {
        return enabled;
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

    public String getKingbaseTunnelHost() {
        return kingbaseTunnelHost;
    }

    public int getKingbaseTunnelPort() {
        return kingbaseTunnelPort;
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
