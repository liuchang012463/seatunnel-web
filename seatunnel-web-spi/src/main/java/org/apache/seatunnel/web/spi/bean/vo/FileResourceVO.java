package org.apache.seatunnel.web.spi.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.Date;

/** Public metadata for one reusable file resource. */
@Data
public class FileResourceVO {

    /** Snowflake IDs exceed JavaScript's safe integer range. */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Integer ownerId;

    private String providerType;

    private String bucket;

    private String objectKey;

    /** Logical path exposed below the configured provider root. */
    private String logicalPath;

    private String name;

    /** FILE or DIRECTORY. */
    private String resourceType;

    private Long size;

    private String contentType;

    private String etag;

    /** READY or DELETED. */
    private String status;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date updateTime;
}
