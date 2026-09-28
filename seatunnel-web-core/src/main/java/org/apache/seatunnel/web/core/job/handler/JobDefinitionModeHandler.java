package org.apache.seatunnel.web.core.job.handler;

import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.modal.JobDefinitionAnalysisResult;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;

public interface JobDefinitionModeHandler {

    /**
     * Match only by job definition mode:
     * SCRIPT / GUIDE_SINGLE / GUIDE_MULTI
     */
    boolean supports(JobDefinitionMode mode);

    /**
     * Validate the definition content for the current mode.
     */
    void validate(JobDefinitionSaveCommand command);

    /**
     * Analyze summary information such as source/sink type,
     * table name, datasource ID, and other metadata.
     */
    JobDefinitionAnalysisResult analyze(JobDefinitionSaveCommand command);

    /**
     * Serialize the job definition content.
     *
     * SCRIPT       -> ScriptJobContent JSON
     * GUIDE_SINGLE -> workflow JSON
     * GUIDE_MULTI  -> GuideMultiJobContent JSON
     */
    String serializeDefinition(JobDefinitionSaveCommand command);

    /**
     * Build the SeaTunnel HOCON configuration.
     */
    String buildHoconConfig(JobDefinitionSaveCommand command);
}
