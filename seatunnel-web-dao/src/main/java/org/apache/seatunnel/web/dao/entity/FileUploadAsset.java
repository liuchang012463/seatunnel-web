package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** Metadata for one browser-uploaded object. */
@Data
@TableName("t_seatunnel_web_file_upload_asset")
public class FileUploadAsset {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String sessionId;

    private String relativePath;

    private String objectKey;

    private String originalName;

    private Long size;

    private String etag;

    private String contentType;

    private String status;

    private Date createTime;

    private Date updateTime;
}
