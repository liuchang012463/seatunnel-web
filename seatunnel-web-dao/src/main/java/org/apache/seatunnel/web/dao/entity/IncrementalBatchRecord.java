package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** Immutable window identity plus the job instance associated with it. */
@Data
@TableName("t_seatunnel_web_incremental_batch_record")
public class IncrementalBatchRecord {

    @TableId(value = "batch_id", type = IdType.INPUT)
    private String batchId;

    private Long jobDefinitionId;

    private Long jobInstanceId;

    private Date windowStart;

    private Date windowEnd;

    private Date queryStart;

    private String batchStatus;

    private Integer retryCount;

    private Date startedAt;

    private Date finishedAt;

    private String errorMessage;

    private Date createTime;

    private Date updateTime;
}
