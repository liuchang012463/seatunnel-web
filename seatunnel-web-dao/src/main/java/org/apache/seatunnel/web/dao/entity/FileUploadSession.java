package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Control-plane record for files uploaded from the browser for one batch job.
 *
 * <p>The session id is deliberately opaque.  The object storage prefix is
 * retained here so cleanup never has to infer a user supplied path.</p>
 */
@Data
@TableName("t_seatunnel_web_file_upload_session")
public class FileUploadSession {

    @TableId
    private String id;

    private Long jobDefinitionId;

    private Integer ownerId;

    private String objectPrefix;

    private String status;

    private Date expiresAt;

    private Date createTime;

    private Date updateTime;
}
