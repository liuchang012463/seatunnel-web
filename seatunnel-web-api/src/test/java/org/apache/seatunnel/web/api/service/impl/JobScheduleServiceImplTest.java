package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.common.enums.ScheduleStatusEnum;
import org.apache.seatunnel.web.common.enums.TaskExecutionMode;
import org.apache.seatunnel.web.dao.entity.JobSchedule;
import org.apache.seatunnel.web.dao.repository.JobScheduleDao;
import org.apache.seatunnel.web.spi.bean.dto.SeaTunnelJobScheduleDTO;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class JobScheduleServiceImplTest {

    private Scheduler scheduler;
    private JobScheduleDao scheduleDao;
    private JobScheduleServiceImpl scheduleService;

    @BeforeEach
    void setUp() {
        scheduler = mock(Scheduler.class);
        scheduleDao = mock(JobScheduleDao.class);
        scheduleService = new JobScheduleServiceImpl(scheduler);
        ReflectionTestUtils.setField(scheduleService, "jobScheduleDao", scheduleDao);
    }

    @Test
    void manualScheduleNeverCreatesQuartzTriggerWhenStarted() throws SchedulerException {
        JobSchedule schedule = manualSchedule(21L);
        when(scheduleDao.queryById(21L)).thenReturn(schedule);

        assertTrue(scheduleService.startSchedule(21L));

        verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
        verify(scheduler, never()).triggerJob(any(JobKey.class));
        verify(scheduleDao).updateScheduleStatus(21L, ScheduleStatusEnum.PAUSE);
        verify(scheduleDao).updateNextScheduleTime(21L, null);
    }

    @Test
    void manualScheduleDefensivelyClearsLegacyCronWhenStarted() throws SchedulerException {
        JobSchedule schedule = manualSchedule(24L);
        schedule.setCronExpression("0 0 2 * * ?");
        when(scheduleDao.queryById(24L)).thenReturn(schedule);
        when(scheduleDao.updateById(any(JobSchedule.class))).thenReturn(true);

        assertTrue(scheduleService.startSchedule(24L));

        verify(scheduleDao).updateById(org.mockito.ArgumentMatchers.argThat(normalized ->
                normalized.getExecutionMode() == TaskExecutionMode.MANUAL
                        && normalized.getCronExpression() == null
                        && normalized.getScheduleStatus() == ScheduleStatusEnum.PAUSE
                        && normalized.getNextScheduleTime() == null));
        verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    void manualScheduleCannotBeTriggeredThroughQuartzApi() throws SchedulerException {
        when(scheduleDao.queryById(22L)).thenReturn(manualSchedule(22L));

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> scheduleService.triggerSchedule(22L)
        );

        assertTrue(error.getMessage().contains("没有 Quartz"));
        verify(scheduler, never()).triggerJob(any(JobKey.class));
    }

    @Test
    void manualSchedulePersistenceClearsCronAndPausesStatus() throws SchedulerException {
        when(scheduleDao.existsByJobDefinitionId(23L)).thenReturn(false);
        when(scheduleDao.insert(any(JobSchedule.class))).thenAnswer(invocation -> {
            JobSchedule schedule = invocation.getArgument(0);
            schedule.setId(23L);
            return 1;
        });

        SeaTunnelJobScheduleDTO dto = new SeaTunnelJobScheduleDTO();
        dto.setJobDefinitionId(23L);
        dto.setExecutionMode(TaskExecutionMode.MANUAL);
        dto.setCronExpression("0 0 2 * * ?");
        dto.setScheduleStatus(ScheduleStatusEnum.NORMAL);
        JobScheduleConfig config = new JobScheduleConfig();
        config.setExecutionMode(TaskExecutionMode.MANUAL);
        config.setCronExpression("0 0 2 * * ?");
        dto.setScheduleConfig(config);

        assertEquals(23L, scheduleService.createTaskSchedule(dto));

        verify(scheduleDao).insert(org.mockito.ArgumentMatchers.argThat(schedule ->
                schedule.getExecutionMode() == TaskExecutionMode.MANUAL
                        && schedule.getCronExpression() == null
                        && schedule.getScheduleStatus() == ScheduleStatusEnum.PAUSE
                        && schedule.getScheduleConfig().contains("MANUAL")));
        verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    private JobSchedule manualSchedule(Long id) {
        JobSchedule schedule = new JobSchedule();
        schedule.setId(id);
        schedule.setExecutionMode(TaskExecutionMode.MANUAL);
        schedule.setCronExpression(null);
        schedule.setScheduleStatus(ScheduleStatusEnum.PAUSE);
        return schedule;
    }
}
