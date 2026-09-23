package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Request to start a direct multipart upload for one resource file. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "File resource multipart upload request")
public class FileResourceMultipartUploadRequestDTO {

    private String path;

    private String relativePath;

    private Long size;

    private String contentType;
}
