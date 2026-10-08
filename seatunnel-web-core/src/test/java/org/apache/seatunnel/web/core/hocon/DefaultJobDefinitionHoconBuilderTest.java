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
class DefaultJobDefinitionHoconBuilderTest {

    @Test
    void hidesUnexpectedFailureDetailsFromApiAndLogs() {
        String credential = "pwd=supersecret";
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
                throw new IllegalArgumentException(credential);
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
            ServiceException failure = assertThrows(ServiceException.class, () -> builder.build(command));

            assertEquals(Status.BUILD_JOB_INSTANCE_CONFIG_ERROR.getCode(), failure.getCode());
            assertFalse(failure.getMessage().contains(credential));
            assertFalse(appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains(credential)));
            assertEquals("Build job hocon config failed, mode=GUIDE_SINGLE, failureType=IllegalArgumentException",
                    appender.list.get(0).getFormattedMessage());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
