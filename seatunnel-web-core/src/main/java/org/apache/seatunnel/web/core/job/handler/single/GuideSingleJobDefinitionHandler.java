package org.apache.seatunnel.web.core.job.handler.single;

import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.modal.JobDefinitionAnalysisResult;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.job.handler.JobDefinitionModeHandler;
import org.apache.seatunnel.web.spi.bean.dto.command.GuideSingleJobContentCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class GuideSingleJobDefinitionHandler implements JobDefinitionModeHandler {

    private final GuideSingleWorkflowValidator workflowValidator;
    private final GuideSingleWorkflowAnalyzer workflowAnalyzer;
    private final GuideSingleHoconBuildService hoconBuildService;

    public GuideSingleJobDefinitionHandler(
            GuideSingleWorkflowValidator workflowValidator,
            GuideSingleWorkflowAnalyzer workflowAnalyzer,
            GuideSingleHoconBuildService hoconBuildService) {
        this.workflowValidator = workflowValidator;
        this.workflowAnalyzer = workflowAnalyzer;
        this.hoconBuildService = hoconBuildService;
    }

    @Override
    public boolean supports(JobDefinitionMode mode) {
        return JobDefinitionMode.GUIDE_SINGLE == mode || JobDefinitionMode.FILE_SYNC == mode;
    }

    @Override
    public void validate(JobDefinitionSaveCommand command) {
        GuideSingleJobContentCommand cmd = cast(command);
        workflowValidator.validate(cmd.getWorkflow());
        if (command.getMode() == JobDefinitionMode.FILE_SYNC) {
            validateFileSync(cmd.getWorkflow());
        }
    }

    @Override
    public JobDefinitionAnalysisResult analyze(JobDefinitionSaveCommand command) {
        GuideSingleJobContentCommand cmd = cast(command);
        return workflowAnalyzer.analyze(cmd.getWorkflow());
    }

    @Override
    public String serializeDefinition(JobDefinitionSaveCommand command) {
        GuideSingleJobContentCommand cmd = cast(command);
        return JSONUtils.toJsonString(cmd.getWorkflow());
    }

    @Override
    public String buildHoconConfig(JobDefinitionSaveCommand command) {
        GuideSingleJobContentCommand cmd = cast(command);
        return hoconBuildService.build(cmd.getWorkflow(), command);
    }

    private GuideSingleJobContentCommand cast(JobDefinitionSaveCommand command) {
        if (!(command instanceof GuideSingleJobContentCommand)) {
            throw new IllegalArgumentException("command must implement GuideSingleJobContentCommand");
        }
        return (GuideSingleJobContentCommand) command;
    }

    @SuppressWarnings("unchecked")
    private void validateFileSync(Map<String, Object> workflow) {
        Object rawNodes = workflow.get("nodes");
        if (!(rawNodes instanceof List)) {
            throw new IllegalArgumentException("FILE_SYNC workflow nodes are required");
        }
        Map<String, Object> source = null;
        Map<String, Object> sink = null;
        for (Object rawNode : (List<?>) rawNodes) {
            if (!(rawNode instanceof Map)) { continue; }
            Object rawData = ((Map<?, ?>) rawNode).get("data");
            if (!(rawData instanceof Map)) { continue; }
            Map<String, Object> data = (Map<String, Object>) rawData;
            Object rawConfig = data.get("config");
            Map<String, Object> config = rawConfig instanceof Map ? (Map<String, Object>) rawConfig : data;
            if ("source".equals(String.valueOf(data.get("nodeType")))) { source = config; }
            if ("sink".equals(String.valueOf(data.get("nodeType")))) { sink = config; }
        }
        if (source == null || sink == null) {
            throw new IllegalArgumentException("FILE_SYNC requires exactly one source and one sink");
        }
        requireFileDbType(source, "source");
        requireFileDbType(sink, "sink");
        if ("INCREMENTAL".equalsIgnoreCase(String.valueOf(source.get("syncType")))
                && !String.valueOf(source.get("dataSourceId")).equals(String.valueOf(sink.get("dataSourceId")))) {
            throw new IllegalArgumentException("FILE_SYNC incremental mode requires the same source and target datasource");
        }
    }

    private void requireFileDbType(Map<String, Object> config, String role) {
        String dbType = String.valueOf(config.get("dbType"));
        if (!"FTP".equalsIgnoreCase(dbType) && !"SFTP".equalsIgnoreCase(dbType)) {
            throw new IllegalArgumentException("FILE_SYNC " + role + " dbType must be FTP or SFTP");
        }
    }
}
