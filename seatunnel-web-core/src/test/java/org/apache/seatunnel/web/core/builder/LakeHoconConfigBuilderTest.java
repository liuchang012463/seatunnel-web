package org.apache.seatunnel.web.core.builder;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.dag.DagGraph;
import org.apache.seatunnel.web.core.builder.sink.DataSourceSinkBuilder;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.spi.bean.dto.config.BatchJobEnvConfig;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

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
