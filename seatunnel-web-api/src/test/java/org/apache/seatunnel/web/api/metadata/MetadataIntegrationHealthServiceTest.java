package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataHealth;
import org.apache.seatunnel.web.spi.bean.vo.MetadataIntegrationHealthVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MetadataIntegrationHealthServiceTest {

    @Test
    void reportsHealthyOnlyWhenServerAndManagedBuildMatch() {
        OpenMetadataProperties properties = properties(true);
        OpenMetadataClient client = mock(OpenMetadataClient.class);
        when(client.health()).thenReturn(new OpenMetadataHealth(true, true, "1.12.10", "1.12.10.0"));

        MetadataIntegrationHealthVO result = new MetadataIntegrationHealthService(properties, client).health();

        assertEquals("UP", result.getOpenMetadata());
        assertEquals("UP", result.getOrchestrator());
        assertTrue(result.isVersionCompatible());
        assertEquals("1.12.10.x", result.getExpectedVersionLine());
    }

    @Test
    void marksVersionMismatchWithoutExposingIntegrationException() {
        OpenMetadataProperties properties = properties(true);
        OpenMetadataClient client = mock(OpenMetadataClient.class);
        when(client.health()).thenReturn(new OpenMetadataHealth(true, true, "1.13.0", "1.13.0.0"));

        MetadataIntegrationHealthVO result = new MetadataIntegrationHealthService(properties, client).health();

        assertFalse(result.isVersionCompatible());
        assertEquals("1.13.0", result.getVersion());
    }

    @Test
    void reportsDisabledWithoutCallingOpenMetadata() {
        OpenMetadataProperties properties = properties(false);
        OpenMetadataClient client = mock(OpenMetadataClient.class);

        MetadataIntegrationHealthVO result = new MetadataIntegrationHealthService(properties, client).health();

        assertEquals("DISABLED", result.getOpenMetadata());
        assertEquals("DISABLED", result.getOrchestrator());
        assertFalse(result.isVersionCompatible());
        verifyNoInteractions(client);
    }

    private static OpenMetadataProperties properties(boolean enabled) {
        OpenMetadataProperties properties = new OpenMetadataProperties();
        properties.setEnabled(enabled);
        properties.setExpectedServerVersion("1.12.10");
        properties.setExpectedIngestionPatch("1.12.10.0");
        return properties;
    }
}
