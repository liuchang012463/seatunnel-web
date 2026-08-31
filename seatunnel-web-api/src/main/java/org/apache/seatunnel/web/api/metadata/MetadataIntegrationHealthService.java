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

    private final OpenMetadataProperties properties;
    private final OpenMetadataClient openMetadataClient;

    public MetadataIntegrationHealthService(
            OpenMetadataProperties properties, OpenMetadataClient openMetadataClient) {
        this.properties = properties;
        this.openMetadataClient = openMetadataClient;
    }

    public MetadataIntegrationHealthVO health() {
        MetadataIntegrationHealthVO result = new MetadataIntegrationHealthVO();
        result.setExpectedVersion(properties.getExpectedServerVersion());
        result.setExpectedVersionLine(EXPECTED_INGESTION_LINE);
        if (!properties.isEnabled()) {
            result.setOpenMetadata("DISABLED");
            result.setOrchestrator("DISABLED");
            result.setVersion(properties.getExpectedServerVersion());
            result.setIngestionVersion(properties.getExpectedIngestionPatch());
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
                        && properties.getExpectedServerVersion().equals(health.serverVersion())
                        && properties.getExpectedIngestionPatch().equals(health.ingestionVersion()));
        return result;
    }
}
