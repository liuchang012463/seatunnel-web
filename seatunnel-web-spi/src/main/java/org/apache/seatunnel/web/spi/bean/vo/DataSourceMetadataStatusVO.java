package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** Cached control-plane state for one existing DataSource. */
@Data
public class DataSourceMetadataStatusVO {

    /** READY/PENDING/...; historical rows without a Binding use NOT_INITIALIZED. */
    private String syncStatus;

    /** Whether this data source type can collect OpenMetadata sample data at all. */
    private boolean sampleDataSupported;

    /** Operator decision for OpenMetadata sample-data collection; off by default. */
    private boolean sampleDataEnabled;

    /** Whether this data source type derives container data models from a manifest. */
    private boolean storageManifestSupported;

    /** Configured object-storage manifest JSON; null when none is set. */
    private String storageManifest;

    private MetadataRunStateVO scan;

    private MetadataRunStateVO exploration;
}
