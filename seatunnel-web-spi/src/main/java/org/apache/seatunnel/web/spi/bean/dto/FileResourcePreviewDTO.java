package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Options for the bounded preview of one reusable file resource. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "File resource preview request")
public class FileResourcePreviewDTO {

    /** Maximum number of rows returned by the preview. */
    private Integer limit = 20;

    /** csv, excel, json, or text; when absent the resource extension is used. */
    private String fileFormatType;

    private String encoding = "UTF-8";

    private String fieldDelimiter;

    private String sheetName;
}
