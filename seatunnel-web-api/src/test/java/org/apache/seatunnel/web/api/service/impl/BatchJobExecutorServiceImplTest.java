package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.metrics.BatchJobSubmitter;
import org.apache.seatunnel.web.api.service.BatchJobDefinitionService;
import org.apache.seatunnel.web.api.service.BatchJobInstanceService;
import org.apache.seatunnel.web.api.service.IncrementalBatchService;
import org.apache.seatunnel.web.api.service.JobScheduleService;
import org.apache.seatunnel.web.common.enums.ReleaseState;
import org.apache.seatunnel.web.common.enums.RunMode;
import org.apache.seatunnel.web.dao.entity.JobSchedule;
import org.apache.seatunnel.web.spi.bean.vo.BatchJobDefinitionVO;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchJobExecutorServiceImplTest {

    @Test
    void manualExecutionRecordsLastRunTimeOnItsSchedule() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        BatchJobDefinitionService definitionService = mock(BatchJobDefinitionService.class);
        BatchJobSubmitter jobSubmitter = mock(BatchJobSubmitter.class);
        IncrementalBatchService incrementalBatchService = mock(IncrementalBatchService.class);
        JobScheduleService jobScheduleService = mock(JobScheduleService.class);

        BatchJobExecutorServiceImpl executor = new BatchJobExecutorServiceImpl(
                instanceService,
                definitionService,
                jobSubmitter,
                incrementalBatchService,
                jobScheduleService
        );

        BatchJobDefinitionVO definition = new BatchJobDefinitionVO();
        definition.setId(301L);
        definition.setReleaseState(ReleaseState.ONLINE);

        JobInstanceVO instance = new JobInstanceVO();
        instance.setId(401L);

        JobSchedule schedule = new JobSchedule();
        schedule.setId(501L);

        when(definitionService.selectById(301L)).thenReturn(definition);
        when(incrementalBatchService.prepare(301L)).thenReturn(null);
        when(instanceService.create(301L, RunMode.MANUAL, null)).thenReturn(instance);
        when(jobScheduleService.getByTaskDefinitionId(301L)).thenReturn(schedule);
        when(jobScheduleService.updateLastScheduleTime(501L)).thenReturn(true);

        assertEquals(401L, executor.jobExecute(301L, RunMode.MANUAL));

        verify(jobSubmitter).submit(any(JobInstanceVO.class));
        verify(jobScheduleService).updateLastScheduleTime(501L);
    }
}
