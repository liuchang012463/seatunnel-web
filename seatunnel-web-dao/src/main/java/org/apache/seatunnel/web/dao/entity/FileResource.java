package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Control-plane metadata for one reusable object-storage file or directory. */
@Data
@TableName("t_seatunnel_web_file_resource")
@EqualsAndHashCode(callSuper = true)
public class FileResource extends BaseEntity {

    private Integer ownerId;

    private String providerType;

    private String bucket;

    private String objectKey;

    private String logicalPath;

    private String logicalPathHash;

    private String name;

    private String resourceType;

    private Long size;

    private String contentType;

    private String etag;

    private String status;
}
