package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.service.support.JobInstanceFactory;
import org.apache.seatunnel.web.common.enums.RunMode;
import org.apache.seatunnel.web.core.hocon.JobDefinitionCommandResolver;
import org.apache.seatunnel.web.core.hocon.JobDefinitionHoconBuilder;
import org.apache.seatunnel.web.dao.entity.JobDefinitionEntity;
import org.apache.seatunnel.web.dao.entity.JobInstance;
import org.apache.seatunnel.web.dao.repository.JobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchJobInstanceServiceImplSnapshotTest {

    @Mock private JobInstanceDao jobInstanceDao;
    @Mock private JobDefinitionDao jobDefinitionDao;
    @Mock private JobInstanceFactory jobInstanceFactory;
    @Mock private JobDefinitionHoconBuilder jobDefinitionHoconBuilder;
    @Mock private JobDefinitionCommandResolver jobDefinitionCommandResolver;

    @InjectMocks private BatchJobInstanceServiceImpl service;

    /**
     * The instance row is the immutable identity of one run: the lineage service reads it back
     * while the definition may already be renamed or edited.
     */
    @Test
    void createCapturesTheSubmissionIdentitySnapshot() {
        JobDefinitionSaveCommand command = org.mockito.Mockito.mock(JobDefinitionSaveCommand.class);
        when(command.getId()).thenReturn(7L);
        when(jobDefinitionCommandResolver.resolve(7L)).thenReturn(command);
        when(jobDefinitionHoconBuilder.build(command)).thenReturn("env {}");
        when(jobInstanceFactory.create(any(), anyLong(), anyString(), any(), anyString(), any()))
                .thenReturn(new JobInstance());
        JobDefinitionEntity definition = new JobDefinitionEntity();
        definition.setId(7L);
        definition.setJobName("orders sync");
        definition.setSourceDatasourceId(10L);
        definition.setSinkDatasourceId(20L);
        definition.setSourceTable("db.orders");
        definition.setSinkTable("ods.orders_dwd");
        when(jobDefinitionDao.queryById(7L)).thenReturn(definition);
        when(jobInstanceDao.insert(any())).thenReturn(1);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baseLogDir", "logs");

        service.create(7L, RunMode.MANUAL, null);

        ArgumentCaptor<JobInstance> captured = ArgumentCaptor.forClass(JobInstance.class);
        verify(jobInstanceDao).insert(captured.capture());
        JobInstance instance = captured.getValue();
        assertEquals("orders sync", instance.getJobName());
        assertEquals(10L, instance.getSourceDatasourceId());
        assertEquals(20L, instance.getSinkDatasourceId());
        assertEquals("db.orders", instance.getSourceTable());
        assertEquals("ods.orders_dwd", instance.getSinkTable());
    }
}
