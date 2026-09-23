package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** ETags required to commit a multipart file resource upload. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "File resource multipart completion request")
public class FileResourceMultipartCompleteRequestDTO {

    private List<FileResourceMultipartPartETagDTO> parts;
}
