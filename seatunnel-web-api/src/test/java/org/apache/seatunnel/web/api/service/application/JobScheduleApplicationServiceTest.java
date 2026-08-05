package org.apache.seatunnel.web.api.service.application;

import org.apache.seatunnel.web.api.service.JobScheduleService;
import org.apache.seatunnel.web.common.enums.ScheduleStatusEnum;
import org.apache.seatunnel.web.common.enums.TaskExecutionMode;
import org.apache.seatunnel.web.dao.entity.JobSchedule;
import org.apache.seatunnel.web.spi.bean.dto.SeaTunnelJobScheduleDTO;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleIncrementalJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig.ScheduleParamItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.SchedulerException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.argThat;

class JobScheduleApplicationServiceTest {

    private JobScheduleService scheduleService;
    private JobScheduleApplicationService applicationService;

    @BeforeEach
    void setUp() {
        scheduleService = mock(JobScheduleService.class);
        applicationService = new JobScheduleApplicationService();
        ReflectionTestUtils.setField(applicationService, "jobScheduleService", scheduleService);
    }

    @Test
    void savesManualScheduleWithoutCronOrQuartzTriggerAndKeepsParams() throws SchedulerException {
        JobSchedule existing = new JobSchedule();
        existing.setId(11L);
        when(scheduleService.getByTaskDefinitionId(101L)).thenReturn(existing);
        when(scheduleService.updateScheduleStatus(11L, ScheduleStatusEnum.PAUSE)).thenReturn(true);

        JobScheduleConfig config = manualConfigWithLegacyCron();
        BatchGuideSingleJobSaveCommand command = new BatchGuideSingleJobSaveCommand();
        command.setSchedule(config);

        applicationService.saveOrUpdateSchedule(101L, command);

        verify(scheduleService).updateTaskSchedule(argThat(dto ->
                dto.getExecutionMode() == TaskExecutionMode.MANUAL
                        && dto.getCronExpression() == null
                        && dto.getScheduleStatus() == ScheduleStatusEnum.PAUSE
                        && dto.getScheduleConfig().getExecutionMode() == TaskExecutionMode.MANUAL
                        && dto.getScheduleConfig().getParamsList().get(0).getParamValue().equals("${biz_date}")));
        verify(scheduleService).stopSchedule(11L);
        verify(scheduleService).updateScheduleStatus(11L, ScheduleStatusEnum.PAUSE);
        verify(scheduleService, never()).startSchedule(any());
        verify(scheduleService, never()).getLast5ExecutionTimesByCron(any());
        verify(scheduleService, times(0)).createTaskSchedule(any());
    }

    @Test
    void savesAutoSchedulePausedUntilReleaseAndValidatesCron() throws SchedulerException {
        when(scheduleService.getByTaskDefinitionId(102L)).thenReturn(null);
        when(scheduleService.createTaskSchedule(any(SeaTunnelJobScheduleDTO.class))).thenReturn(12L);
        when(scheduleService.updateScheduleStatus(12L, ScheduleStatusEnum.PAUSE)).thenReturn(true);
        when(scheduleService.getLast5ExecutionTimesByCron("0 0 2 * * ?"))
                .thenReturn(List.of("2026-08-06 02:00:00"));

        JobScheduleConfig config = new JobScheduleConfig();
        config.setExecutionMode(TaskExecutionMode.AUTO);
        config.setCronExpression(" 0 0 2 * * ? ");
        config.setScheduleRunType("normal");
        BatchGuideSingleJobSaveCommand command = new BatchGuideSingleJobSaveCommand();
        command.setSchedule(config);

        applicationService.saveOrUpdateSchedule(102L, command);

        verify(scheduleService).createTaskSchedule(argThat(dto ->
                dto.getExecutionMode() == TaskExecutionMode.AUTO
                        && "0 0 2 * * ?".equals(dto.getCronExpression())
                        && dto.getScheduleStatus() == ScheduleStatusEnum.PAUSE));
        verify(scheduleService).getLast5ExecutionTimesByCron("0 0 2 * * ?");
        verify(scheduleService).stopSchedule(12L);
        verify(scheduleService, never()).startSchedule(any());
    }

    @Test
    void rejectsManualIncrementalSchedule() throws SchedulerException {
        JobScheduleConfig config = new JobScheduleConfig();
        config.setExecutionMode(TaskExecutionMode.MANUAL);
        BatchGuideSingleIncrementalJobSaveCommand command =
                new BatchGuideSingleIncrementalJobSaveCommand();
        command.setSchedule(config);

        assertThrows(
                RuntimeException.class,
                () -> applicationService.saveOrUpdateSchedule(103L, command)
        );
        verify(scheduleService, never()).createTaskSchedule(any());
        verify(scheduleService, never()).updateTaskSchedule(any());
    }

    private JobScheduleConfig manualConfigWithLegacyCron() {
        ScheduleParamItem param = new ScheduleParamItem();
        param.setKey("bizDate");
        param.setParamName("业务日期");
        param.setParamValue("${biz_date}");

        JobScheduleConfig config = new JobScheduleConfig();
        config.setExecutionMode(TaskExecutionMode.MANUAL);
        config.setCronExpression("0 0 2 * * ?");
        config.setScheduleRunType("normal");
        config.setParamsList(List.of(param));
        return config;
    }
}
