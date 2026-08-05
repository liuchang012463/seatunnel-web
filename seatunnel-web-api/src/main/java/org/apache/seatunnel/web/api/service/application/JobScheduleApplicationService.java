package org.apache.seatunnel.web.api.service.application;

import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.service.JobScheduleService;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.enums.ScheduleStatusEnum;
import org.apache.seatunnel.web.common.enums.TaskExecutionMode;
import org.apache.seatunnel.web.dao.entity.JobSchedule;
import org.apache.seatunnel.web.spi.bean.dto.SeaTunnelJobScheduleDTO;
import org.apache.seatunnel.web.spi.bean.dto.command.BatchJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;
import org.quartz.SchedulerException;
import org.springframework.stereotype.Service;

@Service
public class JobScheduleApplicationService {

    @Resource
    private JobScheduleService jobScheduleService;

    public void saveOrUpdateSchedule(Long jobDefinitionId, BatchJobSaveCommand command) {
        if (jobDefinitionId == null || command == null) {
            return;
        }

        JobScheduleConfig scheduleConfig = command.getSchedule();
        if (scheduleConfig == null) {
            scheduleConfig = new JobScheduleConfig();
        }

        TaskExecutionMode executionMode = scheduleConfig.resolveExecutionMode();
        if (command.getMode() == JobDefinitionMode.GUIDE_SINGLE_INCREMENTAL
                && (executionMode != TaskExecutionMode.AUTO
                || StringUtils.isBlank(scheduleConfig.getCronExpression()))) {
            throw new RuntimeException("单表增量任务必须配置自动调度和有效 Cron");
        }

        ScheduleStatusEnum scheduleStatus;
        if (executionMode == TaskExecutionMode.MANUAL) {
            // A legacy client may still submit an old Cron or NORMAL status.
            // Normalize it before persisting so no stale trigger can be reused.
            scheduleConfig.setExecutionMode(TaskExecutionMode.MANUAL);
            scheduleConfig.setCronExpression(null);
            scheduleConfig.setScheduleRunType(ScheduleStatusEnum.PAUSE.getDesc());
            scheduleStatus = ScheduleStatusEnum.PAUSE;
        } else {
            if (StringUtils.isBlank(scheduleConfig.getCronExpression())) {
                throw new RuntimeException("自动调度必须配置有效 Cron");
            }
            scheduleConfig.setExecutionMode(TaskExecutionMode.AUTO);
            scheduleConfig.setCronExpression(scheduleConfig.getCronExpression().trim());
            try {
                jobScheduleService.getLast5ExecutionTimesByCron(scheduleConfig.getCronExpression());
            } catch (RuntimeException e) {
                throw new RuntimeException("自动调度 Cron 无效", e);
            }
            // A definition is still offline after save. Quartz is started by
            // the release-state transition, never by saving the definition.
            scheduleConfig.setScheduleRunType(ScheduleStatusEnum.PAUSE.getDesc());
            scheduleStatus = ScheduleStatusEnum.PAUSE;
        }

        JobSchedule existing = jobScheduleService.getByTaskDefinitionId(jobDefinitionId);

        SeaTunnelJobScheduleDTO scheduleDTO = buildScheduleDTO(
                jobDefinitionId,
                scheduleConfig,
                scheduleStatus,
                existing
        );

        try {
            Long scheduleId = saveSchedule(scheduleDTO, existing);

            // 保存阶段只清理旧 trigger；自动调度要等任务上线后再启动。
            refreshQuartzState(scheduleId);

            // Keep every saved definition paused until it is explicitly online.
            boolean updated = jobScheduleService.updateScheduleStatus(scheduleId, scheduleStatus);
            if (!updated) {
                throw new RuntimeException("Failed to update final schedule status");
            }
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to save or update schedule", e);
        }
    }

    public void removeSchedule(Long jobDefinitionId) {
        jobScheduleService.removeByDefinitionId(jobDefinitionId);
    }

    public JobSchedule getByTaskDefinitionId(Long jobDefinitionId) {
        return jobScheduleService.getByTaskDefinitionId(jobDefinitionId);
    }

    private SeaTunnelJobScheduleDTO buildScheduleDTO(Long jobDefinitionId,
                                                     JobScheduleConfig scheduleConfig,
                                                     ScheduleStatusEnum scheduleStatus,
                                                     JobSchedule existing) {
        SeaTunnelJobScheduleDTO dto = new SeaTunnelJobScheduleDTO();
        dto.setJobDefinitionId(jobDefinitionId);
        dto.setExecutionMode(scheduleConfig.resolveExecutionMode());
        dto.setCronExpression(scheduleConfig.getCronExpression() == null
                ? null
                : scheduleConfig.getCronExpression().trim());
        dto.setScheduleStatus(scheduleStatus);
        dto.setScheduleConfig(scheduleConfig);

        if (existing != null) {
            dto.setId(existing.getId());
        }
        return dto;
    }

    private Long saveSchedule(SeaTunnelJobScheduleDTO scheduleDTO, JobSchedule existing)
            throws SchedulerException {
        if (existing == null) {
            return jobScheduleService.createTaskSchedule(scheduleDTO);
        }
        jobScheduleService.updateTaskSchedule(scheduleDTO);
        return existing.getId();
    }

    private void refreshQuartzState(Long scheduleId)
            throws SchedulerException {
        // 无论保存的是哪种执行方式，都先清理旧 trigger，避免旧配置继续触发。
        jobScheduleService.stopSchedule(scheduleId);
    }
}
