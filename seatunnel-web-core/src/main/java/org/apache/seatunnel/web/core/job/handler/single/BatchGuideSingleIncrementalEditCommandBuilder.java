package org.apache.seatunnel.web.core.job.handler.single;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.job.handler.BatchJobEditCommandBuilder;
import org.apache.seatunnel.web.core.time.IncrementalConfigResolver;
import org.apache.seatunnel.web.dao.entity.JobDefinitionContentEntity;
import org.apache.seatunnel.web.dao.entity.JobDefinitionEntity;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleIncrementalJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.BatchJobEnvConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.JobBasicConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

/** Builds the independent edit command for an incremental single-table job. */
@Component
public class BatchGuideSingleIncrementalEditCommandBuilder implements BatchJobEditCommandBuilder {

    @Override
    public JobDefinitionMode mode() {
        return JobDefinitionMode.GUIDE_SINGLE_INCREMENTAL;
    }

    @Override
    public JobDefinitionSaveCommand build(JobDefinitionEntity definition,
                                          JobDefinitionContentEntity contentEntity,
                                          JobScheduleConfig scheduleConfig) {
        BatchGuideSingleIncrementalJobSaveCommand command = new BatchGuideSingleIncrementalJobSaveCommand();
        command.setId(definition.getId());
        JobBasicConfig basic = new JobBasicConfig();
        basic.setMode(definition.getMode());
        basic.setJobName(definition.getJobName());
        basic.setJobDesc(definition.getJobDesc());
        basic.setClientId(definition.getClientId());
        command.setBasic(basic);
        command.setSchedule(scheduleConfig);
        command.setEnv(JSONUtils.parseObject(contentEntity.getEnvConfig(), BatchJobEnvConfig.class));
        Map<String, Object> workflow = JSONUtils.parseObject(
                contentEntity.getDefinitionContent(), new TypeReference<Map<String, Object>>() {});
        if (workflow == null) {
            workflow = Collections.emptyMap();
        } else {
            IncrementalConfigResolver.normalizeLegacySourceConfig(workflow, scheduleConfig);
        }
        command.setWorkflow(workflow);
        return command;
    }
}
