package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.metrics.BatchJobSubmitter;
import org.apache.seatunnel.web.api.lake.job.LakeJobGuard;
import org.apache.seatunnel.web.api.service.BatchJobDefinitionService;
import org.apache.seatunnel.web.api.service.BatchJobInstanceService;
import org.apache.seatunnel.web.api.service.IncrementalBatchService;
import org.apache.seatunnel.web.api.service.JobScheduleService;
import org.apache.seatunnel.web.api.metrics.JobResultHandler;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.common.enums.ReleaseState;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.RunMode;
import org.apache.seatunnel.web.dao.entity.JobSchedule;
import org.apache.seatunnel.web.spi.bean.vo.BatchJobDefinitionVO;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BatchJobExecutorServiceImplTest {

    @Test
    void manualExecutionQueuesSubmissionAndRecordsLastRunTimeOnItsSchedule() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        BatchJobDefinitionService definitionService = mock(BatchJobDefinitionService.class);
        BatchJobSubmitter jobSubmitter = mock(BatchJobSubmitter.class);
        IncrementalBatchService incrementalBatchService = mock(IncrementalBatchService.class);
        JobScheduleService jobScheduleService = mock(JobScheduleService.class);
        LakeJobGuard lakeJobGuard = mock(LakeJobGuard.class);
        JobResultHandler jobResultHandler = mock(JobResultHandler.class);
        List<Runnable> queuedSubmissions = new ArrayList<>();
        TaskExecutor submitExecutor = queuedSubmissions::add;

        BatchJobExecutorServiceImpl executor = new BatchJobExecutorServiceImpl(
                instanceService,
                definitionService,
                jobSubmitter,
                incrementalBatchService,
                jobScheduleService,
                lakeJobGuard,
                jobResultHandler,
                submitExecutor
        );

        BatchJobDefinitionVO definition = new BatchJobDefinitionVO();
        definition.setId(301L);
        definition.setReleaseState(ReleaseState.ONLINE);

        JobInstanceVO instance = new JobInstanceVO();
        instance.setId(401L);
        instance.setJobDefinitionId(301L);

        JobSchedule schedule = new JobSchedule();
        schedule.setId(501L);

        when(definitionService.selectById(301L)).thenReturn(definition);
        when(incrementalBatchService.prepare(301L)).thenReturn(null);
        when(instanceService.create(301L, RunMode.MANUAL, null)).thenReturn(instance);
        when(jobScheduleService.getByTaskDefinitionId(301L)).thenReturn(schedule);
        when(jobScheduleService.updateLastScheduleTime(501L)).thenReturn(true);

        assertEquals(401L, executor.jobExecute(301L, RunMode.MANUAL));

        assertEquals(1, queuedSubmissions.size());
        verifyNoInteractions(jobSubmitter);
        assertThrows(ServiceException.class, () -> executor.jobExecute(301L, RunMode.MANUAL));
        assertEquals(1, queuedSubmissions.size());

        queuedSubmissions.get(0).run();

        verify(jobSubmitter).submit(instance);
        verify(jobScheduleService).updateLastScheduleTime(501L);
        verify(lakeJobGuard).validateBeforeExecute(301L, LakeJobRuntimeType.BATCH);
        verify(lakeJobGuard).validateBeforeSubmit(instance, LakeJobRuntimeType.BATCH);
        verify(incrementalBatchService).prepare(301L);
        verify(instanceService).create(301L, RunMode.MANUAL, null);
    }
}
