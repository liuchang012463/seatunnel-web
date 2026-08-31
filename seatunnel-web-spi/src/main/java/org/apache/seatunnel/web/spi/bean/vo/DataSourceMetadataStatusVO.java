package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** Cached control-plane state for one existing DataSource. */
@Data
public class DataSourceMetadataStatusVO {

    /** READY/PENDING/...; historical rows without a Binding use NOT_INITIALIZED. */
    private String syncStatus;

    private MetadataRunStateVO scan;

    private MetadataRunStateVO exploration;
}
