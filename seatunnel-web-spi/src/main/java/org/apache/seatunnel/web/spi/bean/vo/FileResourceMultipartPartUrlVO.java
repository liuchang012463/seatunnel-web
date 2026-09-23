package org.apache.seatunnel.web.spi.bean.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Short-lived URL for uploading one S3-compatible multipart part. */
@Data
@AllArgsConstructor
public class FileResourceMultipartPartUrlVO {

    private Integer partNumber;

    private String url;
}
