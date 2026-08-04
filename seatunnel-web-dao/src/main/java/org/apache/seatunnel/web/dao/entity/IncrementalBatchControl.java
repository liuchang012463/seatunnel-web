package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/** Persistent watermark and coarse-grained state for one incremental job. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_seatunnel_web_incremental_batch_control")
public class IncrementalBatchControl extends BaseEntity {

    private Long jobDefinitionId;

    private Date committedWatermark;

    private String lastSuccessBatchId;

    private String taskStatus;

    private Integer versionNo;
}
