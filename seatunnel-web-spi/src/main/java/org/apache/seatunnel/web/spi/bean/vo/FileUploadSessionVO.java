package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.List;

@Data
public class FileUploadSessionVO {
    private String id;
    private Long jobDefinitionId;
    private String status;
    private List<FileUploadAssetVO> assets;
}
