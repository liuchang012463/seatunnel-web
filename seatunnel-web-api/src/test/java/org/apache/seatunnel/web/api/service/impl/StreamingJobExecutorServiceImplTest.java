package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.job.LakeJobGuard;
import org.apache.seatunnel.web.api.metrics.streaming.StreamingJobSubmitter;
import org.apache.seatunnel.web.api.service.StreamingJobInstanceService;
import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.ReleaseState;
import org.apache.seatunnel.web.common.enums.RunMode;
import org.apache.seatunnel.web.dao.entity.StreamingJobDefinitionEntity;
import org.apache.seatunnel.web.dao.entity.StreamingJobInstance;
import org.apache.seatunnel.web.engine.client.handler.ZetaJobStatusHandler;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamingJobExecutorServiceImplTest {

    @Test
    void executeValidatesLakeDefinitionBeforeCreatingInstance() {
        StreamingJobInstanceService instanceService = mock(StreamingJobInstanceService.class);
        StreamingJobDefinitionQueryService definitionQueryService =
                mock(StreamingJobDefinitionQueryService.class);
        StreamingJobSubmitter submitter = mock(StreamingJobSubmitter.class);
        ZetaJobStatusHandler statusHandler = mock(ZetaJobStatusHandler.class);
        LakeJobGuard guard = mock(LakeJobGuard.class);

        StreamingJobExecutorServiceImpl executor = new StreamingJobExecutorServiceImpl(
                instanceService, definitionQueryService, submitter, statusHandler, guard);

        StreamingJobDefinitionEntity definition = new StreamingJobDefinitionEntity();
        definition.setId(301L);
        definition.setReleaseState(ReleaseState.ONLINE);
        when(definitionQueryService.getDefinitionOrThrow(301L)).thenReturn(definition);
        when(instanceService.existsRunningInstance(301L)).thenReturn(false);
        when(instanceService.lastInstance(301L)).thenReturn(null);

        JobInstanceVO instance = new JobInstanceVO();
        instance.setId(401L);
        when(instanceService.create(301L, RunMode.MANUAL, JobMode.STREAMING)).thenReturn(instance);

        assertEquals(401L, executor.jobExecute(301L, RunMode.MANUAL));

        var order = inOrder(guard, instanceService, submitter);
        order.verify(guard).validateBeforeExecute(301L, LakeJobRuntimeType.STREAMING);
        order.verify(instanceService).create(301L, RunMode.MANUAL, JobMode.STREAMING);
        order.verify(submitter).submit(instance);
    }

    @Test
    void resumeFromSavepointValidatesBeforeCreatingReplacementInstance() {
        StreamingJobInstanceService instanceService = mock(StreamingJobInstanceService.class);
        StreamingJobDefinitionQueryService definitionQueryService =
                mock(StreamingJobDefinitionQueryService.class);
        StreamingJobSubmitter submitter = mock(StreamingJobSubmitter.class);
        ZetaJobStatusHandler statusHandler = mock(ZetaJobStatusHandler.class);
        LakeJobGuard guard = mock(LakeJobGuard.class);

        StreamingJobExecutorServiceImpl executor = new StreamingJobExecutorServiceImpl(
                instanceService, definitionQueryService, submitter, statusHandler, guard);

        JobInstanceVO source = new JobInstanceVO();
        source.setId(501L);
        source.setJobDefinitionId(301L);
        source.setJobStatus("CANCELED");
        source.setSavepointPath("zeta://savepoint/job/engine-1");
        when(instanceService.selectById(501L)).thenReturn(source);

        StreamingJobDefinitionEntity definition = new StreamingJobDefinitionEntity();
        definition.setId(301L);
        definition.setReleaseState(ReleaseState.ONLINE);
        when(definitionQueryService.getDefinitionOrThrow(301L)).thenReturn(definition);
        when(instanceService.existsRunningInstance(301L)).thenReturn(false);

        JobInstanceVO replacement = new JobInstanceVO();
        replacement.setId(601L);
        when(instanceService.create(301L, RunMode.MANUAL, JobMode.STREAMING)).thenReturn(replacement);

        assertEquals(601L, executor.jobResumeFromSavepoint(501L, RunMode.MANUAL));

        var order = inOrder(guard, instanceService, submitter);
        order.verify(guard).validateBeforeExecute(301L, LakeJobRuntimeType.STREAMING);
        order.verify(instanceService).create(301L, RunMode.MANUAL, JobMode.STREAMING);
        order.verify(submitter).submitFromSavepoint(replacement, "engine-1");
        verify(instanceService).updateById(org.mockito.ArgumentMatchers.any(StreamingJobInstance.class));
    }
}
