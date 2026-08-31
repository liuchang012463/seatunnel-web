package org.apache.seatunnel.web.core.builder.sink;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.core.builder.context.DagBuildContext;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

class LakeDorisSinkBuilderTest {

    private static final long DORIS_DATA_SOURCE_ID = 99L;
    private static final long BINDING_ID = 7L;

    private DataSourceDao dataSourceDao;
    private LakeOdsDatabaseBindingDao bindingDao;
    private DataSourceSinkBuilder builder;

    @BeforeEach
    void setUp() {
        dataSourceDao = Mockito.mock(DataSourceDao.class);
        bindingDao = Mockito.mock(LakeOdsDatabaseBindingDao.class);
        builder = new DataSourceSinkBuilder();
        ReflectionTestUtils.setField(builder, "dataSourceDao", dataSourceDao);
        ReflectionTestUtils.setField(builder, "lakeOdsDatabaseBindingDao", bindingDao);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("seatunnel.lake.data-source-id", String.valueOf(DORIS_DATA_SOURCE_ID));
        ReflectionTestUtils.setField(builder, "environment", environment);
        when(dataSourceDao.queryById(DORIS_DATA_SOURCE_ID)).thenReturn(dorisDataSource());
    }

    @Test
    void structuredSinkUsesBindingDatabaseInsteadOfClientDatabase() {
        when(bindingDao.queryActiveById(BINDING_ID)).thenReturn(binding(LakeResourceStatus.READY));

        Config result = builder.build(node("client_supplied_database"),
                DagBuildContext.empty(null, BINDING_ID));

        assertEquals("ods_from_binding", result.getString("database"));
        assertEquals("orders", result.getString("table"));
    }

    @Test
    void bindingMustBeReady() {
        when(bindingDao.queryActiveById(BINDING_ID)).thenReturn(binding(LakeResourceStatus.CREATING));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> builder.build(node("client_supplied_database"),
                        DagBuildContext.empty(null, BINDING_ID)));

        assertEquals(true, exception.getMessage().contains("active and READY"));
    }

    @Test
    void bindingMustPointToTheStructuredSinkDataSource() {
        LakeOdsDatabaseBinding binding = binding(LakeResourceStatus.READY);
        binding.setLakeDataSourceId(100L);
        when(bindingDao.queryActiveById(BINDING_ID)).thenReturn(binding);

        assertThrows(IllegalArgumentException.class,
                () -> builder.build(node("client_supplied_database"),
                        DagBuildContext.empty(null, BINDING_ID)));
    }

    @Test
    void bindingMustPointToConfiguredLakeDataSource() {
        LakeOdsDatabaseBinding binding = binding(LakeResourceStatus.READY);
        binding.setLakeDataSourceId(100L);
        when(bindingDao.queryActiveById(BINDING_ID)).thenReturn(binding);

        // The structured sink id is deliberately changed with the binding so
        // this assertion reaches the configured Lake DataSource gate.
        when(dataSourceDao.queryById(100L)).thenReturn(dorisDataSource());
        assertThrows(IllegalArgumentException.class,
                () -> builder.build(node("client_supplied_database", 100L),
                        DagBuildContext.empty(null, BINDING_ID)));
    }

    @Test
    void ordinarySinkWithoutBindingKeepsClientDatabase() {
        Config result = builder.build(node("ordinary_database"), DagBuildContext.empty());

        assertEquals("ordinary_database", result.getString("database"));
    }

    @Test
    void bindingRequiresConfiguredLakeDataSource() {
        when(bindingDao.queryActiveById(BINDING_ID)).thenReturn(binding(LakeResourceStatus.READY));
        ReflectionTestUtils.setField(builder, "environment", new MockEnvironment());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> builder.build(node("client_supplied_database"),
                        DagBuildContext.empty(null, BINDING_ID)));

        assertEquals("Lake Doris data source is not configured", exception.getMessage());
    }

    private Config node(String clientDatabase) {
        return node(clientDatabase, DORIS_DATA_SOURCE_ID);
    }

    private Config node(String clientDatabase, long dataSourceId) {
        return ConfigFactory.parseString(
                "dataSourceId = \"" + dataSourceId + "\"\n"
                        + "dbType = \"DORIS\"\n"
                        + "pluginName = \"DORIS\"\n"
                        + "connectorType = \"Doris\"\n"
                        + "database = \"" + clientDatabase + "\"\n"
                        + "targetTableName = \"orders\"\n");
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

    private LakeOdsDatabaseBinding binding(LakeResourceStatus status) {
        LakeOdsDatabaseBinding binding = new LakeOdsDatabaseBinding();
        binding.setLakeDataSourceId(DORIS_DATA_SOURCE_ID);
        binding.setDatabaseName("ods_from_binding");
        binding.setResourceStatus(status);
        return binding;
    }
}
