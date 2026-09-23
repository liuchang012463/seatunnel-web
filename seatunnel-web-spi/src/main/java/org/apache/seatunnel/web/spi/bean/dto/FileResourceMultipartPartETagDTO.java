package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** ETag returned by MinIO after uploading one part. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Completed file resource multipart part")
public class FileResourceMultipartPartETagDTO {

    private Integer partNumber;

    private String etag;
}
