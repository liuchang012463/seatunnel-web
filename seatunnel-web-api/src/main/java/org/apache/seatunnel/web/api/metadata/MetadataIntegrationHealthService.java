package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataHealth;
import org.apache.seatunnel.web.spi.bean.vo.MetadataIntegrationHealthVO;
import org.springframework.stereotype.Service;

/**
 * Operator-facing health projection for the fixed OpenMetadata integration.
 * It deliberately returns a stable, non-sensitive response when the control
 * plane is unavailable instead of exposing a connection exception to users.
 */
@Service
public class MetadataIntegrationHealthService {

    private static final String EXPECTED_INGESTION_LINE = "1.12.10.x";

    private final OpenMetadataConfigResolver configResolver;
    private final OpenMetadataClient openMetadataClient;

    @org.springframework.beans.factory.annotation.Autowired
    public MetadataIntegrationHealthService(
            OpenMetadataConfigResolver configResolver, OpenMetadataClient openMetadataClient) {
        this.configResolver = configResolver;
        this.openMetadataClient = openMetadataClient;
    }

    /** Backward-compatible constructor for older unit tests. */
    public MetadataIntegrationHealthService(
            OpenMetadataProperties properties, OpenMetadataClient openMetadataClient) {
        this(OpenMetadataConfigResolver.fixed(properties), openMetadataClient);
    }

    public MetadataIntegrationHealthVO health() {
        OpenMetadataRuntimeConfig runtime = configResolver.resolve();
        MetadataIntegrationHealthVO result = new MetadataIntegrationHealthVO();
        result.setExpectedVersion(runtime.getExpectedServerVersion());
        result.setExpectedVersionLine(EXPECTED_INGESTION_LINE);
        if (!runtime.isEnabled()) {
            result.setOpenMetadata("DISABLED");
            result.setOrchestrator("DISABLED");
            result.setVersion(runtime.getExpectedServerVersion());
            result.setIngestionVersion(runtime.getExpectedIngestionPatch());
            result.setVersionCompatible(false);
            return result;
        }

        OpenMetadataHealth health = openMetadataClient.health();
        if (health == null) {
            result.setOpenMetadata("DOWN");
            result.setOrchestrator("DOWN");
            result.setVersionCompatible(false);
            return result;
        }
        result.setOpenMetadata(health.openMetadataUp() ? "UP" : "DOWN");
        result.setOrchestrator(health.orchestratorUp() ? "UP" : "DOWN");
        result.setVersion(health.serverVersion());
        result.setIngestionVersion(health.ingestionVersion());
        result.setVersionCompatible(
                health.openMetadataUp()
                        && health.orchestratorUp()
                        && runtime.getExpectedServerVersion().equals(health.serverVersion())
                        && runtime.getExpectedIngestionPatch().equals(health.ingestionVersion()));
        return result;
    }
}
