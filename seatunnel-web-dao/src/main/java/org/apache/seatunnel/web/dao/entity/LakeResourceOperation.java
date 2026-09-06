package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.LakeOperationStatus;
import org.apache.seatunnel.web.common.enums.LakeOperationType;

import java.util.Date;

@Data
@TableName("t_seatunnel_web_lake_resource_operation")
@EqualsAndHashCode(callSuper = true)
public class LakeResourceOperation extends BaseEntity {

    private String resourceType;

    private Long resourceId;

    private Integer generation;

    private LakeOperationType operationType;

    private String operationToken;

    private String requestHash;

    private LakeOperationStatus status;

    private Date startedAt;

    private Date finishedAt;

    private String errorCode;

    private String errorSummary;

    private Integer operatorId;
}
