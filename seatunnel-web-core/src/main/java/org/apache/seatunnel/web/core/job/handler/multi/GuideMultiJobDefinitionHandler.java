package org.apache.seatunnel.web.core.job.handler.multi;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.modal.JobDefinitionAnalysisResult;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.job.handler.JobDefinitionModeHandler;
import org.apache.seatunnel.web.core.job.handler.single.GuideSingleHoconBuildService;
import org.apache.seatunnel.web.core.job.handler.single.GuideSingleWorkflowAnalyzer;
import org.apache.seatunnel.web.spi.bean.dto.command.BatchJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.GuideMultiJobContentCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;
import org.springframework.stereotype.Component;

import java.util.function.UnaryOperator;

@Component
@Slf4j
public class GuideMultiJobDefinitionHandler implements JobDefinitionModeHandler {

    private final GuideMultiJobValidator validator;
    private final GuideMultiJobAnalyzer analyzer;
    private final GuideMultiHoconBuildService hoconBuildService;

    @Resource
    private GuideSingleHoconBuildService workflowBuildService;

    @Resource
    private GuideSingleWorkflowAnalyzer workflowAnalyzer;

    public GuideMultiJobDefinitionHandler(
            GuideMultiJobValidator validator,
            GuideMultiJobAnalyzer analyzer,
            GuideMultiHoconBuildService hoconBuildService) {
        this.validator = validator;
        this.analyzer = analyzer;
        this.hoconBuildService = hoconBuildService;
    }

    @Override
    public boolean supports(JobDefinitionMode mode) {
        return JobDefinitionMode.GUIDE_MULTI == mode;
    }

    @Override
    public void validate(JobDefinitionSaveCommand command) {
        GuideMultiJobContent content = cast(command).getContent();
        if (isDatapack(content)) {
            if (!(command instanceof BatchJobSaveCommand)) {
                throw new IllegalArgumentException("离线数据包只支持批任务");
            }
            DatapackMultiWorkflow.assemble(content);
            return;
        }
        validator.validate(content);
    }

    @Override
    public JobDefinitionAnalysisResult analyze(JobDefinitionSaveCommand command) {
        GuideMultiJobContent content = cast(command).getContent();
        if (isDatapack(content)) {
            return workflowAnalyzer.analyze(DatapackMultiWorkflow.assemble(content));
        }
        return analyzer.analyze(content);
    }

    @Override
    public String serializeDefinition(JobDefinitionSaveCommand command) {
        GuideMultiJobContent content = cast(command).getContent();
        return JSONUtils.toJsonString(content);
    }

    @Override
    public String buildHoconConfig(JobDefinitionSaveCommand command) {
        GuideMultiJobContent content = cast(command).getContent();
        if (isDatapack(content)) {
            return buildDatapackConfig(command, content, UnaryOperator.identity());
        }
        return hoconBuildService.build(content, command);
    }

    @Override
    public String buildPreviewConfig(JobDefinitionSaveCommand command, UnaryOperator<String> mask) {
        GuideMultiJobContent content = cast(command).getContent();
        return isDatapack(content) ? buildDatapackConfig(command, content, mask)
                : JobDefinitionModeHandler.super.buildPreviewConfig(command, mask);
    }

    private String buildDatapackConfig(
            JobDefinitionSaveCommand command, GuideMultiJobContent content, UnaryOperator<String> formatBlock) {
        var workflow = DatapackMultiWorkflow.assemble(content);
        var datapack = content.getDatapack();
        boolean autoCreate = datapack.getFiles() != null;
        int fileCount = autoCreate ? datapack.getFiles().size() : datapack.getMappings().size();
        log.info("Build datapack batch job, definitionId={}, packageResourceId={}, csvCount={}, autoCreate={}",
                command.getId(), datapack.getPackageResourceId(), fileCount, autoCreate);
        return workflowBuildService.build(workflow, command, true, formatBlock);
    }

    private boolean isDatapack(GuideMultiJobContent content) {
        return content != null && content.getDatapack() != null;
    }

    private GuideMultiJobContentCommand cast(JobDefinitionSaveCommand command) {
        if (!(command instanceof GuideMultiJobContentCommand)) {
            throw new IllegalArgumentException("command must implement GuideMultiJobContentCommand");
        }
        return (GuideMultiJobContentCommand) command;
    }
}
