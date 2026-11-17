package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.dao.entity.LakeWarehouseConfig;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.LakeDataSourceAliasDao;
import org.apache.seatunnel.web.dao.repository.LakeJdbcDriverDao;
import org.apache.seatunnel.web.dao.repository.LakeWarehouseConfigDao;
import org.apache.seatunnel.web.api.lake.LakeJdbcDriverLoader;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.spi.bean.vo.LakeDorisHardwareVO;
import org.apache.seatunnel.web.spi.bean.dto.LakeWarehouseConfigDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LakeWarehouseServiceImplTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql://fe:9030/deleted_ods?useSSL=false",
            "jdbc:mysql://fe:9030?useSSL=false",
            "jdbc:mysql://fe:9030/?useSSL=false"
    })
    void savesTheFirstConfigurationAndProjectionWithoutCreatingADefaultDatabase(
            String jdbcUrl, @TempDir Path directory) throws Exception {
        Path driver = Files.writeString(directory.resolve("mysql.jar"), "test driver");
        LakeWarehouseConfigDao configDao = mock(LakeWarehouseConfigDao.class);
        DataSourceDao dataSourceDao = mock(DataSourceDao.class);
        LakeWarehouseServiceImpl service = new LakeWarehouseServiceImpl(
                configDao, mock(LakeJdbcDriverDao.class), mock(LakeDataSourceAliasDao.class),
                dataSourceDao, mock(CurrentUserProvider.class));
        LakeWarehouseConfigDTO request = new LakeWarehouseConfigDTO();
        request.setJdbcUrl(jdbcUrl);
        request.setUsername("lake");
        request.setPassword("test-password");
        request.setDriverLocation("mysql.jar");
        Connection connection = mock(Connection.class);

        try (var loader = mockStatic(LakeJdbcDriverLoader.class)) {
            loader.when(LakeJdbcDriverLoader::resolveDriverDirectory).thenReturn(directory);
            loader.when(() -> LakeJdbcDriverLoader.resolveLocalPath(anyString())).thenReturn(driver);
            loader.when(() -> LakeJdbcDriverLoader.connect(
                    "jdbc:mysql://fe:9030/?useSSL=false", "lake", "test-password",
                    "com.mysql.cj.jdbc.Driver", "mysql.jar")).thenReturn(connection);

            assertEquals("jdbc:mysql://fe:9030/?useSSL=false", service.saveConfig(request).getJdbcUrl());
            ArgumentCaptor<LakeWarehouseConfig> saved = ArgumentCaptor.forClass(LakeWarehouseConfig.class);
            verify(configDao).insert(saved.capture());
            assertEquals("jdbc:mysql://fe:9030/?useSSL=false", saved.getValue().getJdbcUrl());
            ArgumentCaptor<org.apache.seatunnel.web.dao.entity.DataSource> projection =
                    ArgumentCaptor.forClass(org.apache.seatunnel.web.dao.entity.DataSource.class);
            verify(dataSourceDao).insert(projection.capture());
            var params = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(projection.getValue().getConnectionParams());
            assertEquals("", params.path("database").asText());
            assertEquals("jdbc:mysql://fe:9030/?useSSL=false", params.path("url").asText());
            verify(connection, never()).createStatement();
        }
    }

    @Test
    void clusterStatusDoesNotDependOnTheConfiguredDatabase() throws Exception {
        LakeWarehouseConfigDao configDao = mock(LakeWarehouseConfigDao.class);
        LakeWarehouseConfig config = new LakeWarehouseConfig();
        config.setJdbcUrl("jdbc:mysql://fe:9030/deleted_ods?useSSL=false");
        config.setUsername("lake");
        config.setPassword("test-password");
        config.setDriverClass("com.mysql.cj.jdbc.Driver");
        config.setDriverLocation("/test/mysql.jar");
        when(configDao.querySingleton()).thenReturn(config);
        LakeWarehouseServiceImpl service = new LakeWarehouseServiceImpl(
                configDao, mock(LakeJdbcDriverDao.class), mock(LakeDataSourceAliasDao.class),
                mock(DataSourceDao.class), mock(CurrentUserProvider.class));
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(mock(ResultSetMetaData.class));

        try (var loader = mockStatic(LakeJdbcDriverLoader.class)) {
            loader.when(() -> LakeJdbcDriverLoader.connect(
                    "jdbc:mysql://fe:9030/?useSSL=false", "lake", "test-password",
                    "com.mysql.cj.jdbc.Driver", "/test/mysql.jar")).thenReturn(connection);

            assertEquals("CONNECTED_SUCCESS", service.getDorisStatus().getStatus());
            loader.verify(() -> LakeJdbcDriverLoader.connect(
                    "jdbc:mysql://fe:9030/?useSSL=false", "lake", "test-password",
                    "com.mysql.cj.jdbc.Driver", "/test/mysql.jar"));
        }
    }

    @Test
    void keepsTheSubmittedPasswordInPlaintextForTheFirstConfiguration() {
        assertEquals("doris-secret",
                LakeWarehouseServiceImpl.resolvePassword(null, "doris-secret"));
    }

    @Test
    void keepsTheExistingPasswordWhenUpdateOmitsIt() {
        LakeWarehouseConfig current = new LakeWarehouseConfig();
        current.setPassword("existing-password");

        assertEquals("existing-password",
                LakeWarehouseServiceImpl.resolvePassword(current, "  "));
        assertNull(LakeWarehouseServiceImpl.resolvePassword(null, "  "));
    }

    @Test
    void parsesTheDorisHomeHardwareSummaryIntoDisplaySafeFields() {
        String payload = """
                {"code":0,"data":{"VersionInfo":{"Version":"doris-4.1.2-rc01","BuildInfo":"qa","BuildTime":"2026-06-12"},"HardwareInfo":{"NetworkParameter":"Host name: fe Domain name: fe DNS servers: [127.0.0.11] IPv4 Gateway: 172.26.0.1","Processor":"HUAWEI,Kunpeng 920 2 physical CPU package(s) 96 physical CPU core(s) CPU load: 3.0%","OS":"GNU/Linux Ubuntu 22.04.5 LTS Uptime: 9 days","Memory":"Memory: 107.6 GiB/254.1 GiB Swap used: 16.1 MiB/4.0 GiB","FileSystem":"37.6 TiB of 39.9 TiB free (94.2%)","NetworkInterface":"IPv4 [172.26.0.2] Traffic received: 1.2 GiB, Traffic transmitted: 2.4 GiB","Processes":"Processes: 4, Threads: 22089","Disk":"Disks: /dev/sda"}}}
                """;

        LakeDorisHardwareVO result = LakeWarehouseServiceImpl.parseHardwarePayload(payload);

        assertEquals("doris-4.1.2-rc01", result.getVersion());
        assertEquals("fe", result.getHostName());
        assertEquals("172.26.0.2", result.getIpv4());
        assertEquals("HUAWEI,Kunpeng 920", result.getCpuModel());
        assertEquals(96, result.getCpuCores());
        assertEquals("3.0%", result.getCpuLoad());
        assertEquals("42.3%", result.getMemoryUsedPercent());
        assertEquals("94.2%", result.getFilesystemFreePercent());
        assertEquals(4, result.getProcessCount());
        assertEquals(22089, result.getThreadCount());
    }
}
