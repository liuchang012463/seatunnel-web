package org.apache.seatunnel.web.core.builder;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.dag.DagGraph;
import org.apache.seatunnel.web.core.builder.sink.DataSourceSinkBuilder;
import org.apache.seatunnel.web.core.builder.source.DataSourceSourceBuilder;
import org.apache.seatunnel.web.core.job.handler.JobRuntimeContextFactory;
import org.apache.seatunnel.web.core.job.handler.multi.GuideMultiHoconBuildService;
import org.apache.seatunnel.web.core.job.handler.multi.GuideMultiTableMatchResolver;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideMultiJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.BatchJobEnvConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class LakeHoconConfigBuilderTest {

    private static final long DORIS_DATA_SOURCE_ID = 99L;
    private static final long BINDING_ID = 7L;

    @Test
    void hoconOutputUsesServerResolvedDatabaseForStructuredSink() {
        DataSourceDao dataSourceDao = Mockito.mock(DataSourceDao.class);
        LakeOdsDatabaseBindingDao bindingDao = Mockito.mock(LakeOdsDatabaseBindingDao.class);

        DataSourceSinkBuilder sinkBuilder = new DataSourceSinkBuilder();
        ReflectionTestUtils.setField(sinkBuilder, "dataSourceDao", dataSourceDao);
        ReflectionTestUtils.setField(sinkBuilder, "lakeOdsDatabaseBindingDao", bindingDao);
        ReflectionTestUtils.setField(sinkBuilder, "environment", new MockEnvironment()
                .withProperty("seatunnel.lake.data-source-id", String.valueOf(DORIS_DATA_SOURCE_ID)));

        when(dataSourceDao.queryById(DORIS_DATA_SOURCE_ID)).thenReturn(dorisDataSource());
        when(bindingDao.queryActiveById(BINDING_ID)).thenReturn(readyBinding());

        HoconConfigBuilder hoconBuilder = new HoconConfigBuilder();
        ReflectionTestUtils.setField(hoconBuilder,
                "registry", new NodeConfigBuilderRegistry(List.of(sinkBuilder)));
        ReflectionTestUtils.setField(hoconBuilder, "envConfigBuilder", new EnvConfigBuilder(List.of()));

        DagGraph dag = new DagGraph();
        ObjectNode sink = JSONUtils.parseObject(
                "{\"id\":\"sink\",\"data\":{"
                        + "\"nodeType\":\"sink\",\"dbType\":\"DORIS\","
                        + "\"pluginName\":\"DORIS\",\"connectorType\":\"Doris\","
                        + "\"config\":{\"dataSourceId\":\"99\",\"dbType\":\"DORIS\","
                        + "\"pluginName\":\"DORIS\",\"connectorType\":\"Doris\","
                        + "\"database\":\"client_database\",\"targetTableName\":\"orders\"}}}",
                ObjectNode.class);
        dag.setNodes(List.of(sink));
        dag.setEdges(List.of());

        String output = hoconBuilder.build(dag, new BatchJobEnvConfig(), null, BINDING_ID);

        assertTrue(output.contains("database=\"ods_from_binding\""), output);
        assertFalse(output.contains("client_database"), output);
    }

    @Test
    void wholeDatabaseOdsJobBuildsOneConfigFor450TablesAndCreatesMissingTargets() {
        DataSourceDao dataSourceDao = Mockito.mock(DataSourceDao.class);
        LakeOdsDatabaseBindingDao bindingDao = Mockito.mock(LakeOdsDatabaseBindingDao.class);
        DataSource mysql = new DataSource();
        mysql.setDbType(DbType.MYSQL);
        mysql.setConnectionParams("""
                url = "jdbc:mysql://source.example:3306/sales"
                user = test
                password = test
                database = sales
                """);
        when(dataSourceDao.queryById(1L)).thenReturn(mysql);
        when(dataSourceDao.queryById(DORIS_DATA_SOURCE_ID)).thenReturn(dorisDataSource());
        when(bindingDao.queryActiveById(BINDING_ID)).thenReturn(readyBinding());

        DataSourceSourceBuilder sourceBuilder = new DataSourceSourceBuilder();
        ReflectionTestUtils.setField(sourceBuilder, "dataSourceDao", dataSourceDao);
        DataSourceSinkBuilder sinkBuilder = new DataSourceSinkBuilder();
        ReflectionTestUtils.setField(sinkBuilder, "dataSourceDao", dataSourceDao);
        ReflectionTestUtils.setField(sinkBuilder, "lakeOdsDatabaseBindingDao", bindingDao);
        ReflectionTestUtils.setField(sinkBuilder, "environment", new MockEnvironment()
                .withProperty("seatunnel.lake.data-source-id", String.valueOf(DORIS_DATA_SOURCE_ID)));

        HoconConfigBuilder hoconBuilder = new HoconConfigBuilder();
        ReflectionTestUtils.setField(hoconBuilder, "registry",
                new NodeConfigBuilderRegistry(List.of(sourceBuilder, sinkBuilder)));
        ReflectionTestUtils.setField(hoconBuilder, "envConfigBuilder", new EnvConfigBuilder(List.of()));
        GuideMultiHoconBuildService service = new GuideMultiHoconBuildService();
        ReflectionTestUtils.setField(service, "hoconConfigBuilder", hoconBuilder);
        ReflectionTestUtils.setField(service, "runtimeContextFactory", new JobRuntimeContextFactory());
        ReflectionTestUtils.setField(service, "tableMatchResolver", new GuideMultiTableMatchResolver());

        List<String> tables = IntStream.rangeClosed(1, 450).mapToObj(index -> "orders_" + index).toList();
        GuideMultiJobContent content = new GuideMultiJobContent();
        GuideMultiJobContent.WorkflowSourceConfig source = new GuideMultiJobContent.WorkflowSourceConfig();
        source.setDatasourceId("1");
        source.setDbType("MYSQL");
        source.setConnectorType("Jdbc");
        source.setPluginName("JDBC-MYSQL");
        content.setSource(source);
        GuideMultiJobContent.WorkflowTargetConfig target = new GuideMultiJobContent.WorkflowTargetConfig();
        target.setDatasourceId(String.valueOf(DORIS_DATA_SOURCE_ID));
        target.setDbType("DORIS");
        target.setConnectorType("Doris");
        target.setPluginName("DORIS");
        target.setSchemaSaveMode("CREATE_SCHEMA_WHEN_NOT_EXIST");
        content.setTarget(target);
        GuideMultiJobContent.TableMatchConfig match = new GuideMultiJobContent.TableMatchConfig();
        match.setMode("4");
        match.setTables(tables);
        content.setTableMatch(match);

        BatchGuideMultiJobSaveCommand command = new BatchGuideMultiJobSaveCommand();
        BatchJobEnvConfig env = new BatchJobEnvConfig();
        env.setJobMode(JobMode.BATCH);
        command.setEnv(env);
        command.setContent(content);
        command.setOdsDatabaseBindingId(BINDING_ID);
        Config config = ConfigFactory.parseString(service.build(content, command));

        assertEquals(tables.stream().map(table -> "sales." + table).toList(),
                config.getConfigList("source.Jdbc.table_list").stream()
                        .map(table -> table.getString("table_path"))
                        .toList());
        Config sink = config.getConfig("sink.Doris");
        assertEquals("ods_from_binding", sink.getString("database"));
        assertEquals("${table_name}", sink.getString("table"));
        assertEquals("CREATE_SCHEMA_WHEN_NOT_EXIST", sink.getString("schema_save_mode"));
        assertTrue(sink.getString("save_mode_create_template").contains("enable_duplicate_without_keys_by_default"));
    }

    private DataSource dorisDataSource() {
        DataSource dataSource = new DataSource();
        dataSource.setDbType(DbType.DORIS);
        dataSource.setConnectionParams(
                "fenodes = \"127.0.0.1:8030\"\n"
                        + "user = \"test\"\n"
                        + "password = \"test\"\n"
                        + "database = \"client_database\"");
        return dataSource;
    }

    private LakeOdsDatabaseBinding readyBinding() {
        LakeOdsDatabaseBinding binding = new LakeOdsDatabaseBinding();
        binding.setLakeDataSourceId(DORIS_DATA_SOURCE_ID);
        binding.setDatabaseName("ods_from_binding");
        binding.setResourceStatus(LakeResourceStatus.READY);
        return binding;
    }
}
