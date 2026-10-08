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

    private MetadataRunStateVO scan;

    private MetadataRunStateVO exploration;
}
