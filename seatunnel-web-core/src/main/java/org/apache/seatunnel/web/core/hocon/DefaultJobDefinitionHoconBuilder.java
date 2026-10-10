package org.apache.seatunnel.web.core.hocon;


import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.core.job.handler.JobDefinitionModeHandler;
import org.apache.seatunnel.web.core.job.registry.JobDefinitionModeHandlerRegistry;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default hocon builder implementation based on mode handlers.
 */
@Slf4j
@Component
public class DefaultJobDefinitionHoconBuilder implements JobDefinitionHoconBuilder {

    private static final String CREDENTIAL_MASK = "***";

    /**
     * Credential keys followed by the value they carry, in the shapes build errors echo:
     * {@code key=value}, {@code key: value}, {@code key "value"} and an authorization scheme
     * followed by its token. The key may be a compound such as {@code accessKeyId} or
     * {@code dbPassword}, so the keyword is matched as a prefix of the key.
     */
    private static final Pattern CREDENTIAL_VALUE = Pattern.compile(
            "(?i)(password|passwd|pwd|secretkey|secret|accesskey|token|apikey|jaas"
                    + "|authorization|credential|privatekey|signature|bearer|sasl)([\\w.-]*?)"
                    + "(\\s*[:=]\\s*|\\s+(?=[\"']))"
                    + "(?:(bearer|basic|digest)\\s+)?"
                    + "(\"[^\"]*\"|'[^']*'|[^\\s\"']+)");

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
                    command.getMode(), rootCauseType(e), sanitizedCause, e);
            throw new ServiceException(
                    Status.BUILD_JOB_INSTANCE_CONFIG_ERROR.getCode(),
                    Status.BUILD_JOB_INSTANCE_CONFIG_ERROR.getMsg() + ": " + sanitizedCause,
                    e);
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
     * Extract the deepest root cause message for the user, masking only the values that follow a
     * credential key. Replacing the whole message would hide actionable text whenever a sensitive
     * word happens to be part of it, e.g. an HTTP schema field named {@code token}.
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
        String sanitized = maskCredentialValues(message.replaceAll("\\s+", " ").trim());
        return StringUtils.abbreviate(sanitized, 200);
    }

    private String maskCredentialValues(String message) {
        Matcher matcher = CREDENTIAL_VALUE.matcher(message);
        StringBuilder masked = new StringBuilder();
        while (matcher.find()) {
            String scheme = matcher.group(4);
            String replacement = matcher.group(1) + matcher.group(2) + matcher.group(3)
                    + (scheme == null ? "" : scheme + " ") + CREDENTIAL_MASK;
            matcher.appendReplacement(masked, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(masked);
        return masked.toString();
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
