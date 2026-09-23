package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/** Audit/control-plane record for one reusable file resource upload request. */
@Data
@TableName("t_seatunnel_web_file_upload_record")
@EqualsAndHashCode(callSuper = true)
public class FileUploadRecord extends BaseEntity {

    private Integer ownerId;

    private String providerType;

    private String bucket;

    private String targetPath;

    private String logicalPath;

    private String objectKey;

    private String contentType;

    private String multipartUploadId;

    private Long partSize;

    private Integer totalFiles;

    private Long totalSize;

    private String status;

    private String errorMessage;

    private Date finishTime;
}
