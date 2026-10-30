package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.log.JobLogService;
import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.common.enums.RunMode;
import org.apache.seatunnel.web.core.hocon.JobDefinitionHoconBuilder;
import org.apache.seatunnel.web.core.hocon.StreamingJobDefinitionCommandResolver;
import org.apache.seatunnel.web.dao.entity.StreamingJobDefinitionEntity;
import org.apache.seatunnel.web.dao.entity.StreamingJobInstance;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.JobBasicConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamingJobInstanceServiceImplTest {

    private final StreamingJobInstanceDao instanceDao = mock(StreamingJobInstanceDao.class);
    private final StreamingJobDefinitionDao definitionDao = mock(StreamingJobDefinitionDao.class);
    private final JobDefinitionHoconBuilder hoconBuilder = mock(JobDefinitionHoconBuilder.class);
    private final StreamingJobDefinitionCommandResolver resolver =
            mock(StreamingJobDefinitionCommandResolver.class);

    /**
     * The instance row is the immutable identity of one run: the lineage service reads it back
     * while the definition may already be edited.
     */
    @Test
    void createCapturesTheSubmissionIdentitySnapshot() {
        JobDefinitionSaveCommand command = mock(JobDefinitionSaveCommand.class);
        JobBasicConfig basic = new JobBasicConfig();
        basic.setClientId(2L);
        when(command.getId()).thenReturn(7L);
        when(command.getBasic()).thenReturn(basic);
        when(resolver.resolve(7L)).thenReturn(command);
        when(hoconBuilder.build(command)).thenReturn("env {}");
        StreamingJobDefinitionEntity definition = new StreamingJobDefinitionEntity();
        definition.setId(7L);
        definition.setJobName("stream orders sync");
        definition.setSourceDatasourceId(10L);
        definition.setSinkDatasourceId(20L);
        definition.setSourceTable("orders-topic");
        definition.setSinkTable("orders-dwd-topic");
        when(definitionDao.queryById(7L)).thenReturn(definition);
        when(instanceDao.insert(any())).thenReturn(1);

        service().create(7L, RunMode.MANUAL, JobMode.STREAMING);

        ArgumentCaptor<StreamingJobInstance> captured =
                ArgumentCaptor.forClass(StreamingJobInstance.class);
        verify(instanceDao).insert(captured.capture());
        StreamingJobInstance instance = captured.getValue();
        assertEquals("stream orders sync", instance.getJobName());
        assertEquals(10L, instance.getSourceDatasourceId());
        assertEquals(20L, instance.getSinkDatasourceId());
        assertEquals("orders-topic", instance.getSourceTable());
        assertEquals("orders-dwd-topic", instance.getSinkTable());
        assertEquals(JobStatus.STARTING, instance.getJobStatus());
        assertEquals(String.valueOf(instance.getId()), instance.getEngineJobId());
    }

    private StreamingJobInstanceServiceImpl service() {
        StreamingJobInstanceServiceImpl service = new StreamingJobInstanceServiceImpl();
        ReflectionTestUtils.setField(service, "streamingJobInstanceDao", instanceDao);
        ReflectionTestUtils.setField(service, "streamingJobDefinitionDao", definitionDao);
        ReflectionTestUtils.setField(service, "jobDefinitionHoconBuilder", hoconBuilder);
        ReflectionTestUtils.setField(service, "streamingJobDefinitionCommandResolver", resolver);
        ReflectionTestUtils.setField(service, "jobLogService", mock(JobLogService.class));
        ReflectionTestUtils.setField(service, "baseLogDir", "logs");
        return service;
    }
}
