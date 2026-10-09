package org.apache.seatunnel.web.spi.bean.dto;

import lombok.Data;

/**
 * Object-storage manifest submitted for one data source. The value is the same JSON a
 * bucket-level {@code openmetadata.json} would hold; a blank value clears the manifest.
 */
@Data
public class DataSourceStorageManifestDTO {
    private String manifest;
}
