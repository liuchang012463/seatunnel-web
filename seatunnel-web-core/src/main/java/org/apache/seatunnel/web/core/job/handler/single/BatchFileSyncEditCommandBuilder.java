package org.apache.seatunnel.web.core.job.handler.single;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.job.handler.BatchJobEditCommandBuilder;
import org.apache.seatunnel.web.dao.entity.JobDefinitionContentEntity;
import org.apache.seatunnel.web.dao.entity.JobDefinitionEntity;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchFileSyncJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.BatchJobEnvConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.JobBasicConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

@Component
public class BatchFileSyncEditCommandBuilder implements BatchJobEditCommandBuilder {

    @Override
    public JobDefinitionMode mode() {
        return JobDefinitionMode.FILE_SYNC;
    }

    @Override
    public JobDefinitionSaveCommand build(
            JobDefinitionEntity definition,
            JobDefinitionContentEntity contentEntity,
            JobScheduleConfig scheduleConfig) {

        BatchFileSyncJobSaveCommand command = new BatchFileSyncJobSaveCommand();
        command.setId(definition.getId());
        command.setBasic(buildBasicConfig(definition));
        command.setSchedule(scheduleConfig);
        command.setEnv(JSONUtils.parseObject(contentEntity.getEnvConfig(), BatchJobEnvConfig.class));
        Map<String, Object> workflow = JSONUtils.parseObject(
                contentEntity.getDefinitionContent(),
                new TypeReference<Map<String, Object>>() {
                }
        );
        command.setWorkflow(workflow == null ? Collections.emptyMap() : workflow);
        return command;
    }

    private JobBasicConfig buildBasicConfig(JobDefinitionEntity definition) {
        JobBasicConfig basic = new JobBasicConfig();
        basic.setMode(definition.getMode());
        basic.setJobName(definition.getJobName());
        basic.setJobDesc(definition.getJobDesc());
        basic.setClientId(definition.getClientId());
        return basic;
    }
}
