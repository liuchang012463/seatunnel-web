package org.apache.seatunnel.web.core.hocon;


import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.core.job.handler.JobDefinitionModeHandler;
import org.apache.seatunnel.web.core.job.registry.JobDefinitionModeHandlerRegistry;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Default hocon builder implementation based on mode handlers.
 */
@Slf4j
@Component
public class DefaultJobDefinitionHoconBuilder implements JobDefinitionHoconBuilder {

    private static final String[] SENSITIVE_KEYWORDS = {
            "password", "passwd", "pwd", "secret", "accesskey", "secretkey", "token", "apikey",
            "jaas", "authorization", "credential", "privatekey", "signature", "bearer", "sasl"
    };

    private final JobDefinitionModeHandlerRegistry handlerRegistry;

    public DefaultJobDefinitionHoconBuilder(JobDefinitionModeHandlerRegistry handlerRegistry) {
        this.handlerRegistry = handlerRegistry;
    }

    @Override
    public String build(JobDefinitionSaveCommand command) {
        validate(command);

        try {
            JobDefinitionModeHandler handler = handlerRegistry.getHandler(command.getMode());
            handler.validate(command);

            String hocon = handler.buildHoconConfig(command);
            if (StringUtils.isBlank(hocon)) {
                throw new ServiceException(Status.BUILD_JOB_INSTANCE_CONFIG_ERROR, "hocon config is empty");
            }
            return hocon;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            String sanitizedCause = rootCauseMessage(e);
            log.error("Build job hocon config failed, mode={}, failureType={}, cause={}",
                    command.getMode(), rootCauseType(e), sanitizedCause);
            throw new ServiceException(
                    Status.BUILD_JOB_INSTANCE_CONFIG_ERROR.getCode(),
                    Status.BUILD_JOB_INSTANCE_CONFIG_ERROR.getMsg() + ": " + sanitizedCause);
        }
    }

    private String rootCauseType(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    /**
     * Extract the deepest root cause message for the user, without leaking credentials.
     */
    private String rootCauseMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (StringUtils.isBlank(message)) {
            return root.getClass().getSimpleName();
        }
        String sanitized = message.replaceAll("\\s+", " ").trim();
        if (containsSensitiveKeyword(sanitized)) {
            return "请检查任务配置";
        }
        return StringUtils.abbreviate(sanitized, 200);
    }

    private boolean containsSensitiveKeyword(String message) {
        String normalizedMessage = message.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (normalizedMessage.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validate build input.
     */
    private void validate(JobDefinitionSaveCommand command) {
        if (command == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "jobDefinition");
        }
        if (command.getMode() == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "mode");
        }
        if (command.getBasic() == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "basic");
        }
        if (StringUtils.isBlank(command.getBasic().getJobName())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "jobName");
        }
    }
}
