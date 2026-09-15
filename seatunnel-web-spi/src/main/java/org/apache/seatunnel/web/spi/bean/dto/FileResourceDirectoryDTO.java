package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Request for creating one logical directory in the file resource catalog. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "File resource directory request")
public class FileResourceDirectoryDTO {

    /** Absolute logical path below the resource root, for example /incoming/2026. */
    private String path;
}
