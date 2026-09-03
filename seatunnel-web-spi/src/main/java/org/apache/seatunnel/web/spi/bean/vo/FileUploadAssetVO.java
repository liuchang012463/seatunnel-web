package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

@Data
public class FileUploadAssetVO {
    private Long id;
    private String relativePath;
    private String originalName;
    private Long size;
    private String contentType;
    private String status;
}
