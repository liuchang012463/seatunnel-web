package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** Stable, non-sensitive health response for the fixed OpenMetadata integration. */
@Data
public class MetadataIntegrationHealthVO {

    private String openMetadata;
    private String orchestrator;
    private String version;
    private String expectedVersion;
    private String ingestionVersion;
    private String expectedVersionLine;
    private boolean versionCompatible;
}
