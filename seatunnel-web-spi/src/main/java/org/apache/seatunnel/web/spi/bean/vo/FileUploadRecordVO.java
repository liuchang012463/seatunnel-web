package org.apache.seatunnel.web.spi.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.Date;

/** Public audit information for one file resource upload request. */
@Data
public class FileUploadRecordVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Integer ownerId;

    private String providerType;

    private String bucket;

    private String targetPath;

    private Integer totalFiles;

    private Long totalSize;

    /** UPLOADING, SUCCESS, or FAILED. */
    private String status;

    private String errorMessage;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date updateTime;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date finishTime;
}
