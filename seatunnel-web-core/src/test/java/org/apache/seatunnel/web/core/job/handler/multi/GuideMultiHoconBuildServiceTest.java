package org.apache.seatunnel.web.core.job.handler.multi;

import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideMultiHoconBuildServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void multiTableSinkLeavesTablePatternToDialectAwareJdbcBuilder() throws Exception {
        GuideMultiHoconBuildService service = new GuideMultiHoconBuildService();
        GuideMultiJobContent.WorkflowTargetConfig target =
                new GuideMultiJobContent.WorkflowTargetConfig();
        target.setDatasourceId("2");
        target.setDbType("POSTGRE_SQL");
        target.setConnectorType("Jdbc");
        target.setPluginName("JDBC-POSTGRESQL");

        GuideMultiJobContent.TableMatchConfig tableMatch =
                new GuideMultiJobContent.TableMatchConfig();
        tableMatch.setMode("1");

        Method buildSinkNode = GuideMultiHoconBuildService.class.getDeclaredMethod(
                "buildSinkNode",
                GuideMultiJobContent.WorkflowTargetConfig.class,
                GuideMultiJobContent.TableMatchConfig.class,
                java.util.List.class,
                boolean.class,
                org.apache.seatunnel.web.core.job.handler.JobRuntimeContext.class);
        buildSinkNode.setAccessible(true);

        Map<String, Object> node = (Map<String, Object>) buildSinkNode.invoke(
                service, target, tableMatch, Arrays.asList("orders", "items"), false, null);
        Map<String, Object> data = (Map<String, Object>) node.get("data");
        Map<String, Object> config = (Map<String, Object>) data.get("config");

        assertEquals(true, config.get("multiTable"));
        assertTrue(config.containsKey("sink_table_list"));
        assertFalse(config.containsKey("tablePattern"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void jdbcScopeIsPropagatedToSourceAndSinkNodes() throws Exception {
        GuideMultiHoconBuildService service = new GuideMultiHoconBuildService();
        GuideMultiJobContent.WorkflowSourceConfig source =
                new GuideMultiJobContent.WorkflowSourceConfig();
        source.setDatasourceId("1");
        source.setDbType("POSTGRE_SQL");
        source.setConnectorType("Jdbc");
        source.setPluginName("JDBC-POSTGRESQL");
        source.setDatabase("analytics");
        source.setSchemaName("reporting");
        source.setPartitionColumn("order_id");
        source.setPartitionLowerBound("1");
        source.setPartitionUpperBound("1000");
        source.setPartitionNum(4);

        GuideMultiJobContent.WorkflowTargetConfig target =
                new GuideMultiJobContent.WorkflowTargetConfig();
        target.setDatasourceId("2");
        target.setDbType("POSTGRE_SQL");
        target.setConnectorType("Jdbc");
        target.setPluginName("JDBC-POSTGRESQL");
        target.setDatabase("warehouse");
        target.setSchemaName("ods");

        GuideMultiJobContent.TableMatchConfig tableMatch =
                new GuideMultiJobContent.TableMatchConfig();
        tableMatch.setMode("1");

        Method buildSourceNode = GuideMultiHoconBuildService.class.getDeclaredMethod(
                "buildSourceNode",
                GuideMultiJobContent.WorkflowSourceConfig.class,
                GuideMultiJobContent.TableMatchConfig.class,
                List.class,
                boolean.class,
                org.apache.seatunnel.web.core.job.handler.JobRuntimeContext.class);
        buildSourceNode.setAccessible(true);
        Map<String, Object> sourceNode = (Map<String, Object>) buildSourceNode.invoke(
                service, source, tableMatch, List.of("orders"), false, null);

        Method buildSinkNode = GuideMultiHoconBuildService.class.getDeclaredMethod(
                "buildSinkNode",
                GuideMultiJobContent.WorkflowTargetConfig.class,
                GuideMultiJobContent.TableMatchConfig.class,
                List.class,
                boolean.class,
                org.apache.seatunnel.web.core.job.handler.JobRuntimeContext.class);
        buildSinkNode.setAccessible(true);
        Map<String, Object> sinkNode = (Map<String, Object>) buildSinkNode.invoke(
                service, target, tableMatch, List.of("orders"), false, null);

        Map<String, Object> sourceData = (Map<String, Object>) sourceNode.get("data");
        Map<String, Object> sourceConfig = (Map<String, Object>) sourceData.get("config");
        Map<String, Object> sinkData = (Map<String, Object>) sinkNode.get("data");
        Map<String, Object> sinkConfig = (Map<String, Object>) sinkData.get("config");

        assertEquals("analytics", sourceConfig.get("database"));
        assertEquals("reporting", sourceConfig.get("schemaName"));
        assertEquals("order_id", sourceConfig.get("partitionColumn"));
        assertEquals("1", sourceConfig.get("partitionLowerBound"));
        assertEquals("1000", sourceConfig.get("partitionUpperBound"));
        assertEquals(4, sourceConfig.get("partitionNum"));
        assertEquals("warehouse", sinkConfig.get("database"));
        assertEquals("ods", sinkConfig.get("schemaName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mysqlCdcStartupModeAndServerTimeZoneReachSourceConfig() throws Exception {
        GuideMultiHoconBuildService service = new GuideMultiHoconBuildService();
        GuideMultiJobContent.WorkflowSourceConfig source =
                new GuideMultiJobContent.WorkflowSourceConfig();
        source.setDatasourceId("1");
        source.setDbType("MYSQL");
        source.setConnectorType("MySQL-CDC");
        source.setPluginName("MYSQL-CDC");
        source.setStartupMode("latest");
        source.setServerTimeZone("Asia/Shanghai");

        GuideMultiJobContent.TableMatchConfig tableMatch =
                new GuideMultiJobContent.TableMatchConfig();
        tableMatch.setMode("1");

        Method buildSourceNode = GuideMultiHoconBuildService.class.getDeclaredMethod(
                "buildSourceNode",
                GuideMultiJobContent.WorkflowSourceConfig.class,
                GuideMultiJobContent.TableMatchConfig.class,
                List.class,
                boolean.class,
                org.apache.seatunnel.web.core.job.handler.JobRuntimeContext.class);
        buildSourceNode.setAccessible(true);
        Map<String, Object> node = (Map<String, Object>) buildSourceNode.invoke(
                service, source, tableMatch, List.of("orders"), false, null);

        Map<String, Object> data = (Map<String, Object>) node.get("data");
        Map<String, Object> config = (Map<String, Object>) data.get("config");
        assertEquals("latest", config.get("startup.mode"));
        assertEquals("Asia/Shanghai", config.get("serverTimeZone"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dorisSinkSettingsReachSinkConfig() throws Exception {
        GuideMultiHoconBuildService service = new GuideMultiHoconBuildService();
        GuideMultiJobContent.WorkflowTargetConfig target =
                new GuideMultiJobContent.WorkflowTargetConfig();
        target.setDatasourceId("2");
        target.setDbType("DORIS");
        target.setConnectorType("Doris");
        target.setPluginName("DORIS");
        target.setDorisEnable2pc(true);
        target.setDorisLabelPrefix("stable_orders_job");
        target.setDorisReplicaCount(3);

        GuideMultiJobContent.TableMatchConfig tableMatch =
                new GuideMultiJobContent.TableMatchConfig();
        tableMatch.setMode("1");

        Method buildSinkNode = GuideMultiHoconBuildService.class.getDeclaredMethod(
                "buildSinkNode",
                GuideMultiJobContent.WorkflowTargetConfig.class,
                GuideMultiJobContent.TableMatchConfig.class,
                List.class,
                boolean.class,
                org.apache.seatunnel.web.core.job.handler.JobRuntimeContext.class);
        buildSinkNode.setAccessible(true);
        Map<String, Object> node = (Map<String, Object>) buildSinkNode.invoke(
                service, target, tableMatch, List.of("orders"), false, null);

        Map<String, Object> data = (Map<String, Object>) node.get("data");
        Map<String, Object> config = (Map<String, Object>) data.get("config");
        assertEquals(true, config.get("sink.enable-2pc"));
        assertEquals("stable_orders_job", config.get("sink.label-prefix"));
        assertEquals(3, config.get("dorisReplicaCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void postgresCdcWholeDatabaseUsesExpandedExactTableList() throws Exception {
        GuideMultiHoconBuildService service = new GuideMultiHoconBuildService();
        GuideMultiJobContent.WorkflowSourceConfig source =
                new GuideMultiJobContent.WorkflowSourceConfig();
        source.setDatasourceId("1");
        source.setDbType("POSTGRE_SQL");
        source.setConnectorType("Postgres-CDC");
        source.setPluginName("POSTGRESQL-CDC");

        GuideMultiJobContent.TableMatchConfig tableMatch =
                new GuideMultiJobContent.TableMatchConfig();
        tableMatch.setMode("4");

        Method buildSourceNode = GuideMultiHoconBuildService.class.getDeclaredMethod(
                "buildSourceNode",
                GuideMultiJobContent.WorkflowSourceConfig.class,
                GuideMultiJobContent.TableMatchConfig.class,
                List.class,
                boolean.class,
                org.apache.seatunnel.web.core.job.handler.JobRuntimeContext.class);
        buildSourceNode.setAccessible(true);

        Map<String, Object> node = (Map<String, Object>) buildSourceNode.invoke(
                service,
                source,
                tableMatch,
                Arrays.asList("public.orders", "audit.events"),
                false,
                null);
        Map<String, Object> data = (Map<String, Object>) node.get("data");
        Map<String, Object> config = (Map<String, Object>) data.get("config");

        assertEquals("1", config.get("matchMode"));
        assertEquals(Arrays.asList("public.orders", "audit.events"), config.get("source_table_list"));
        assertFalse(config.containsKey("tablePattern"));
    }

    @Test
    void postgresCdcWholeDatabaseRejectsAnEmptyCatalogTableList() throws Exception {
        GuideMultiHoconBuildService service = new GuideMultiHoconBuildService();
        GuideMultiJobContent.WorkflowSourceConfig source =
                new GuideMultiJobContent.WorkflowSourceConfig();
        source.setPluginName("POSTGRESQL-CDC");

        GuideMultiJobContent.TableMatchConfig tableMatch =
                new GuideMultiJobContent.TableMatchConfig();
        tableMatch.setMode("4");

        Method validateTables = GuideMultiHoconBuildService.class.getDeclaredMethod(
                "validateTables",
                GuideMultiJobContent.WorkflowSourceConfig.class,
                GuideMultiJobContent.TableMatchConfig.class,
                List.class,
                List.class,
                boolean.class);
        validateTables.setAccessible(true);

        InvocationTargetException exception = assertThrows(
                InvocationTargetException.class,
                () -> validateTables.invoke(service, source, tableMatch, List.of(), List.of(), false));

        assertTrue(exception.getCause().getMessage().contains("whole database table list is empty"));
    }
}
