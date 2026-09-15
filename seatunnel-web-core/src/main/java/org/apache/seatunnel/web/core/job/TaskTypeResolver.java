package org.apache.seatunnel.web.core.job;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.common.enums.TaskType;
import org.apache.seatunnel.web.common.modal.JobDefinitionAnalysisResult;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;

/**
 * Maps the legacy editor/runtime fields to the stable business task type.
 *
 * <p>The mapping is deliberately centralized because the persisted editor
 * mode and the SeaTunnel runtime mode are not business task categories.</p>
 */
public final class TaskTypeResolver {

    private TaskTypeResolver() {
    }

    public static TaskType resolve(JobDefinitionSaveCommand command,
                                   JobDefinitionAnalysisResult analysis) {
        if (command == null) {
            return null;
        }
        if (command.getRuntimeType() != null
                && "STREAMING".equalsIgnoreCase(command.getRuntimeType().name())) {
            return TaskType.STREAM;
        }
        String sourceType = analysis == null ? null : analysis.getSourceType();
        return resolve(command.getMode(), JobMode.BATCH, sourceType);
    }

    /**
     * Resolve a stored definition, including rows written before task_type
     * existed.  A stored value always wins; legacy fields are only a fallback.
     */
    public static TaskType resolve(TaskType stored,
                                   JobDefinitionMode mode,
                                   JobMode runtimeMode,
                                   String sourceType) {
        if (stored != null) {
            return stored;
        }
        return resolve(mode, runtimeMode, sourceType);
    }

    /** String-based overload for joined instance/list projections. */
    public static TaskType resolve(TaskType stored,
                                   String mode,
                                   String runtimeMode,
                                   String sourceType) {
        return resolve(stored, parseDefinitionMode(mode), parseJobMode(runtimeMode), sourceType);
    }

    private static JobDefinitionMode parseDefinitionMode(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return JobDefinitionMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static JobMode parseJobMode(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return JobMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static TaskType resolve(JobDefinitionMode mode,
                                    JobMode runtimeMode,
                                    String sourceType) {
        if (runtimeMode == JobMode.STREAMING) {
            return TaskType.STREAM;
        }
        if (mode == JobDefinitionMode.FILE_SYNC) {
            return TaskType.FILE_TRANSFER;
        }
        if (StringUtils.equalsAnyIgnoreCase(
                StringUtils.trimToEmpty(sourceType),
                "WEB_UPLOAD",
                "FILE_RESOURCE",
                "LOCAL_FILE")) {
            return TaskType.FILE_INGEST;
        }
        return TaskType.BATCH;
    }
}
