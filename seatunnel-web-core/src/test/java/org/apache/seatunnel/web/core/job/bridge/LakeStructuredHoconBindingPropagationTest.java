package org.apache.seatunnel.web.core.job.bridge;

import org.apache.seatunnel.web.core.builder.HoconConfigBuilder;
import org.apache.seatunnel.web.core.dag.DagGraph;
import org.apache.seatunnel.web.core.job.handler.JobRuntimeContext;
import org.apache.seatunnel.web.core.job.handler.JobRuntimeContextFactory;
import org.apache.seatunnel.web.core.job.handler.multi.GuideMultiHoconBuildService;
import org.apache.seatunnel.web.core.job.handler.multi.GuideMultiTableMatchResolver;
import org.apache.seatunnel.web.core.job.handler.single.GuideSingleHoconBuildService;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideMultiJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleIncrementalJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.BatchJobEnvConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;
import org.apache.seatunnel.web.spi.bean.dto.config.JobEnvConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideSingleJobSaveCommand;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeStructuredHoconBindingPropagationTest {

    private static final long BINDING_ID = 17L;

    @Test
    void singleHoconBuilderReceivesBinding() {
        BatchGuideSingleJobSaveCommand command = new BatchGuideSingleJobSaveCommand();
        command.setOdsDatabaseBindingId(BINDING_ID);

        assertSingleHoconBinding(command, command.getSchedule());
    }

    @Test
    void incrementalSingleHoconBuilderReceivesBinding() {
        BatchGuideSingleIncrementalJobSaveCommand command =
                new BatchGuideSingleIncrementalJobSaveCommand();
        command.setOdsDatabaseBindingId(BINDING_ID);
        command.setSchedule(new JobScheduleConfig());

        assertSingleHoconBinding(command, command.getSchedule());
    }

    @Test
    void streamingSingleHoconBuilderReceivesBinding() {
        StreamingGuideSingleJobSaveCommand command = new StreamingGuideSingleJobSaveCommand();
        command.setOdsDatabaseBindingId(BINDING_ID);

        assertSingleHoconBinding(command, null);
    }

    @Test
    void multiExactHoconBuilderReceivesBinding() {
        BatchGuideMultiJobSaveCommand command = new BatchGuideMultiJobSaveCommand();
        command.setOdsDatabaseBindingId(BINDING_ID);
        command.setContent(multiContent("3", false));

        assertMultiHoconBinding(command);
    }

    @Test
    void wholeDatabaseHoconBuilderReceivesBinding() {
        BatchGuideMultiJobSaveCommand command = new BatchGuideMultiJobSaveCommand();
        command.setOdsDatabaseBindingId(BINDING_ID);
        command.setContent(multiContent("4", true));

        assertMultiHoconBinding(command);
    }

    private void assertSingleHoconBinding(
            org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand command,
            JobScheduleConfig schedule) {
        HoconConfigBuilder hoconBuilder = Mockito.mock(HoconConfigBuilder.class);
        JobRuntimeContextFactory runtimeContextFactory = Mockito.mock(JobRuntimeContextFactory.class);
        JobRuntimeContext context = JobRuntimeContext.builder()
                .env(new JobEnvConfig())
                .schedule(schedule)
                .build();
        when(runtimeContextFactory.create(command)).thenReturn(context);
        when(hoconBuilder.build(
                any(DagGraph.class), any(JobEnvConfig.class), nullable(JobScheduleConfig.class), eq(BINDING_ID)))
                .thenReturn("single-hocon");

        GuideSingleHoconBuildService service = new GuideSingleHoconBuildService();
        ReflectionTestUtils.setField(service, "hoconConfigBuilder", hoconBuilder);
        ReflectionTestUtils.setField(service, "runtimeContextFactory", runtimeContextFactory);

        @SuppressWarnings("unchecked")
        Map<String, Object> workflow = singleWorkflow();
        assertEquals("single-hocon", service.build(workflow, command));

        verify(hoconBuilder).build(
                any(DagGraph.class), any(JobEnvConfig.class), nullable(JobScheduleConfig.class), eq(BINDING_ID));
    }

    private void assertMultiHoconBinding(BatchGuideMultiJobSaveCommand command) {
        HoconConfigBuilder hoconBuilder = Mockito.mock(HoconConfigBuilder.class);
        GuideMultiTableMatchResolver tableMatchResolver = Mockito.mock(GuideMultiTableMatchResolver.class);
        JobRuntimeContextFactory runtimeContextFactory = Mockito.mock(JobRuntimeContextFactory.class);
        when(tableMatchResolver.resolveSourceTables(command.getContent())).thenReturn(List.of("orders"));
        when(tableMatchResolver.resolveSinkTables(command.getContent())).thenReturn(List.of("orders"));
        when(runtimeContextFactory.create(command)).thenReturn(JobRuntimeContext.builder()
                .env(new BatchJobEnvConfig())
                .build());
        when(hoconBuilder.build(
                any(DagGraph.class), any(JobEnvConfig.class), nullable(JobScheduleConfig.class), eq(BINDING_ID)))
                .thenReturn("multi-hocon");

        GuideMultiHoconBuildService service = new GuideMultiHoconBuildService();
        ReflectionTestUtils.setField(service, "hoconConfigBuilder", hoconBuilder);
        ReflectionTestUtils.setField(service, "tableMatchResolver", tableMatchResolver);
        ReflectionTestUtils.setField(service, "runtimeContextFactory", runtimeContextFactory);

        assertEquals("multi-hocon", service.build(command.getContent(), command));
        verify(hoconBuilder).build(
                any(DagGraph.class), any(JobEnvConfig.class), nullable(JobScheduleConfig.class), eq(BINDING_ID));
    }

    private Map<String, Object> singleWorkflow() {
        Map<String, Object> source = new HashMap<>();
        source.put("nodeType", "source");
        source.put("config", new HashMap<>(Map.of(
                "connectorType", "Jdbc", "dbType", "MYSQL", "dataSourceId", "1")));
        Map<String, Object> sink = new HashMap<>();
        sink.put("nodeType", "sink");
        sink.put("config", new HashMap<>(Map.of(
                "connectorType", "Doris", "pluginName", "DORIS", "dbType", "DORIS",
                "dataSourceId", "99", "targetTableName", "orders")));
        return new HashMap<>(Map.of(
                "nodes", List.of(
                        Map.of("id", "source", "data", source),
                        Map.of("id", "sink", "data", sink)),
                "edges", List.of(Map.of("source", "source", "target", "sink"))));
    }

    private GuideMultiJobContent multiContent(String mode, boolean mysqlCdc) {
        GuideMultiJobContent content = new GuideMultiJobContent();
        GuideMultiJobContent.WorkflowSourceConfig source =
                new GuideMultiJobContent.WorkflowSourceConfig();
        source.setDatasourceId("1");
        source.setDbType("MYSQL");
        source.setConnectorType("Jdbc");
        source.setPluginName(mysqlCdc ? "MYSQL-CDC" : "MYSQL");
        content.setSource(source);

        GuideMultiJobContent.WorkflowTargetConfig target =
                new GuideMultiJobContent.WorkflowTargetConfig();
        target.setDatasourceId("99");
        target.setDbType("DORIS");
        target.setConnectorType("Doris");
        target.setPluginName("DORIS");
        target.setOdsDatabaseBindingId(BINDING_ID);
        content.setTarget(target);

        GuideMultiJobContent.TableMatchConfig tableMatch =
                new GuideMultiJobContent.TableMatchConfig();
        tableMatch.setMode(mode);
        content.setTableMatch(tableMatch);
        return content;
    }
}
