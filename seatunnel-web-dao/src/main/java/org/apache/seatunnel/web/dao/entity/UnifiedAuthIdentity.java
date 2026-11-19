package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** External (issuer, subject) identity and optional binding to a local Web user. */
@Data
@TableName("t_seatunnel_web_unified_auth_identity")
public class UnifiedAuthIdentity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String identityKey;
    private String identitySource;
    private String issuer;
    private String subject;
    private String email;
    private String displayName;
    private Integer userId;
    private Date createTime;
    private Date updateTime;
}
