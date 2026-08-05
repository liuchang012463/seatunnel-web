package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.ScheduleStatusEnum;
import org.apache.seatunnel.web.common.enums.TaskExecutionMode;

import java.util.Date;



@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_seatunnel_web_job_schedule")
public class JobSchedule extends BaseEntity {

    /**
     * 任务定义ID
     */
    private Long jobDefinitionId;

    /**
     * MANUAL or AUTO. Legacy rows are inferred from cronExpression when read.
     */
    private TaskExecutionMode executionMode;

    /**
     * Cron表达式
     * 定义任务调度时间规则的Cron表达式
     * 例如：0 0/5 * * * ? 表示每5分钟执行一次
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String cronExpression;

    /**
     * 调度运行模式：NORMAL / PAUSE / EMPTY
     */
    private ScheduleStatusEnum scheduleStatus;

    /**
     * 最后调度时间
     * 最后一次触发调度的时间
     */
    private Date lastScheduleTime;

    /**
     * 下次调度时间
     * 预计下一次触发调度的时间
     */
    private Date nextScheduleTime;


    private String scheduleConfig;
}
