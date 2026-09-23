package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** Part numbers for which the API should create short-lived upload URLs. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "File resource multipart part URL request")
public class FileResourceMultipartPartsRequestDTO {

    private List<Integer> partNumbers;
}
