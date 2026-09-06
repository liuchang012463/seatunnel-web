package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;

import java.util.Date;

/** Common durable state for a resource whose actual state lives in Doris. */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class LakeResourceEntity extends BaseEntity {

    @Version
    private Integer lockVersion = 1;

    private Integer generation = 1;

    private String operationToken;

    private LakeResourceStatus resourceStatus = LakeResourceStatus.PENDING_CREATE;

    private String errorCode;

    private String errorMessage;

    private Date lastReconcileAt;

    private Integer createUserId;

    private Integer updateUserId;

    private Boolean deleted = false;
}
