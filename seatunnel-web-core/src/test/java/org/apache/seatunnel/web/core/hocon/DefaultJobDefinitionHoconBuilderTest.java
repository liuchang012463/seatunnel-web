package org.apache.seatunnel.web.core.hocon;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.modal.JobDefinitionAnalysisResult;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.core.job.handler.JobDefinitionModeHandler;
import org.apache.seatunnel.web.core.job.registry.JobDefinitionModeHandlerRegistry;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.JobBasicConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.JobEnvConfig;
import org.apache.seatunnel.web.spi.enums.JobRuntimeType;
import org.apache.seatunnel.web.spi.enums.Status;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultJobDefinitionHoconBuilderTest {

    @Test
    void redactsCredentialBearingFailuresFromApiAndLogs() {
        String credential = "pwd=supersecret";
        CapturedFailure failure = captureFailure(credential);

        assertEquals(Status.BUILD_JOB_INSTANCE_CONFIG_ERROR.getCode(), failure.exception().getCode());
        assertTrue(failure.exception().getMessage().endsWith(": 请检查任务配置"));
        assertFalse(failure.exception().getMessage().contains(credential));
        assertFalse(failure.logMessages().stream().anyMatch(message -> message.contains(credential)));
        assertEquals(
                "Build job hocon config failed, mode=GUIDE_SINGLE, failureType=IllegalArgumentException, cause=请检查任务配置",
                failure.logMessages().get(0));
    }

    @Test
    void surfacesSanitizedActionableRootCause() {
        CapturedFailure failure = captureFailure(
                "S3File incremental update sync requires SeaTunnel Engine 3.0.0");

        assertEquals(Status.BUILD_JOB_INSTANCE_CONFIG_ERROR.getCode(), failure.exception().getCode());
        assertTrue(failure.exception().getMessage().contains(
                "S3File incremental update sync requires SeaTunnel Engine 3.0.0"));
        assertEquals(
                "Build job hocon config failed, mode=GUIDE_SINGLE, failureType=IllegalArgumentException,"
                        + " cause=S3File incremental update sync requires SeaTunnel Engine 3.0.0",
                failure.logMessages().get(0));
    }

    private CapturedFailure captureFailure(String validateMessage) {
        JobBasicConfig basic = new JobBasicConfig();
        basic.setJobName("test-job");
        JobDefinitionSaveCommand command = new JobDefinitionSaveCommand() {
            @Override
            public Long getId() {
                return null;
            }

            @Override
            public JobDefinitionMode getMode() {
                return JobDefinitionMode.GUIDE_SINGLE;
            }

            @Override
            public JobRuntimeType getRuntimeType() {
                return JobRuntimeType.BATCH;
            }

            @Override
            public JobBasicConfig getBasic() {
                return basic;
            }

            @Override
            public JobEnvConfig getEnv() {
                return null;
            }
        };
        JobDefinitionModeHandler handler = new JobDefinitionModeHandler() {
            @Override
            public boolean supports(JobDefinitionMode mode) {
                return mode == JobDefinitionMode.GUIDE_SINGLE;
            }

            @Override
            public void validate(JobDefinitionSaveCommand ignored) {
                throw new IllegalArgumentException(validateMessage);
            }

            @Override
            public JobDefinitionAnalysisResult analyze(JobDefinitionSaveCommand ignored) {
                return null;
            }

            @Override
            public String serializeDefinition(JobDefinitionSaveCommand ignored) {
                return null;
            }

            @Override
            public String buildHoconConfig(JobDefinitionSaveCommand ignored) {
                return null;
            }
        };
        JobDefinitionModeHandlerRegistry registry = new JobDefinitionModeHandlerRegistry();
        ReflectionTestUtils.setField(registry, "handlers", List.of(handler));

        Logger logger = (Logger) LoggerFactory.getLogger(DefaultJobDefinitionHoconBuilder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            DefaultJobDefinitionHoconBuilder builder = new DefaultJobDefinitionHoconBuilder(registry);
            ServiceException exception = assertThrows(ServiceException.class, () -> builder.build(command));
            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            return new CapturedFailure(exception, messages);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private record CapturedFailure(ServiceException exception, List<String> logMessages) {
    }
}
