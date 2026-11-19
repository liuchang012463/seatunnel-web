package org.apache.seatunnel.web.core.job.handler.multi;

import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.core.hocon.DefaultJobDefinitionHoconBuilder;
import org.apache.seatunnel.web.core.job.registry.JobDefinitionModeHandlerRegistry;
import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;
import org.apache.seatunnel.web.spi.bean.dto.config.JobBasicConfig;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideMultiJobSaveCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GuideMultiJobValidatorTest {

    @Test
    void kafkaFlowDoesNotRequireRelationalTableMatch() {
        GuideMultiJobContent content = new GuideMultiJobContent();

        GuideMultiJobContent.WorkflowSourceConfig source =
                new GuideMultiJobContent.WorkflowSourceConfig();
        source.setDatasourceId("1");
        source.setDbType("KAFKA");
        source.setPluginName("KAFKA");
        content.setSource(source);

        GuideMultiJobContent.WorkflowTargetConfig target =
                new GuideMultiJobContent.WorkflowTargetConfig();
        target.setDatasourceId("2");
        target.setDbType("KAFKA");
        target.setPluginName("KAFKA");
        content.setTarget(target);

        assertDoesNotThrow(() -> new GuideMultiJobValidator().validate(content));
    }

    @Test
    void postgresCdcRejectsSameTargetNameAcrossSchemas() {
        GuideMultiJobContent content = postgresCdcTask(List.of("public.orders", "sales.audit.orders"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new GuideMultiJobValidator().validate(content));
        assertTrue(error.getMessage().contains("public.orders"));
        assertTrue(error.getMessage().contains("sales.audit.orders"));
    }

    @Test
    void runtimeRejectsPreviouslySavedPostgresCdcConflictBeforeBuildingConfig() {
        StreamingGuideMultiJobSaveCommand command = new StreamingGuideMultiJobSaveCommand();
        JobBasicConfig basic = new JobBasicConfig();
        basic.setJobName("saved-postgres-cdc-job");
        command.setBasic(basic);
        command.setContent(postgresCdcTask(List.of("public.orders", "audit.orders")));

        GuideMultiHoconBuildService hoconService = mock(GuideMultiHoconBuildService.class);
        GuideMultiJobDefinitionHandler handler = new GuideMultiJobDefinitionHandler(
                new GuideMultiJobValidator(), new GuideMultiJobAnalyzer(), hoconService);
        JobDefinitionModeHandlerRegistry registry = mock(JobDefinitionModeHandlerRegistry.class);
        when(registry.getHandler(command.getMode())).thenReturn(handler);
        DefaultJobDefinitionHoconBuilder builder = new DefaultJobDefinitionHoconBuilder(registry);

        ServiceException error = assertThrows(ServiceException.class, () -> builder.build(command));
        assertTrue(error.getMessage().contains("public.orders"));
        assertTrue(error.getMessage().contains("audit.orders"));
        verifyNoInteractions(hoconService);
    }

    @Test
    void postgresCdcAllows450DistinctTargetsAcrossSchemas() {
        List<String> tables = IntStream.rangeClosed(1, 450)
                .mapToObj(index -> (index % 2 == 0 ? "public" : "audit") + ".orders_" + index)
                .toList();

        assertDoesNotThrow(() -> new GuideMultiJobValidator().validate(postgresCdcTask(tables)));
    }

    @Test
    void postgresCdcAllowsAliasesForTheSameSourceTable() {
        GuideMultiJobContent content = postgresCdcTask(List.of("public.orders", "sales.public.orders"));

        assertDoesNotThrow(() -> new GuideMultiJobValidator().validate(content));
    }

    private GuideMultiJobContent postgresCdcTask(List<String> tables) {
        GuideMultiJobContent content = new GuideMultiJobContent();
        GuideMultiJobContent.WorkflowSourceConfig source = new GuideMultiJobContent.WorkflowSourceConfig();
        source.setDatasourceId("1");
        source.setDbType("POSTGRE_SQL");
        source.setPluginName("POSTGRESQL-CDC");
        content.setSource(source);

        GuideMultiJobContent.WorkflowTargetConfig target = new GuideMultiJobContent.WorkflowTargetConfig();
        target.setDatasourceId("2");
        target.setDbType("DORIS");
        target.setPluginName("DORIS");
        content.setTarget(target);

        GuideMultiJobContent.TableMatchConfig match = new GuideMultiJobContent.TableMatchConfig();
        match.setMode("4");
        match.setTables(tables);
        content.setTableMatch(match);
        return content;
    }
}
