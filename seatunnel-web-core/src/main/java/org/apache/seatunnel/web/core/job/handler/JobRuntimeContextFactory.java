package org.apache.seatunnel.web.core.job.handler;

import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.dao.entity.SeaTunnelClient;
import org.apache.seatunnel.web.dao.repository.SeaTunnelClientDao;
import org.apache.seatunnel.web.spi.bean.dto.command.BatchJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.StreamingJobSaveCommand;
import org.springframework.stereotype.Component;

@Component
public class JobRuntimeContextFactory {

    @Resource
    private SeaTunnelClientDao seaTunnelClientDao;

    public JobRuntimeContext create(JobDefinitionSaveCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command can not be null");
        }

        JobRuntimeContext.JobRuntimeContextBuilder builder = JobRuntimeContext.builder()
                .runtimeType(command.getRuntimeType())
                .env(command.getEnv())
                .engineVersion(resolveEngineVersion(command));

        if (command instanceof BatchJobSaveCommand) {
            BatchJobSaveCommand batchCommand = (BatchJobSaveCommand) command;
            builder.schedule(batchCommand.getSchedule());
        }

        if (command instanceof StreamingJobSaveCommand) {
            StreamingJobSaveCommand streamingCommand = (StreamingJobSaveCommand) command;
        }

        return builder.build();
    }

    private String resolveEngineVersion(JobDefinitionSaveCommand command) {
        Long clientId = command.getBasic() == null ? null : command.getBasic().getClientId();
        if (clientId == null || seaTunnelClientDao == null) {
            return null;
        }

        SeaTunnelClient client = seaTunnelClientDao.selectById(clientId);
        if (client == null || StringUtils.isBlank(client.getClientVersion())) {
            return null;
        }
        return client.getClientVersion().trim();
    }
}
