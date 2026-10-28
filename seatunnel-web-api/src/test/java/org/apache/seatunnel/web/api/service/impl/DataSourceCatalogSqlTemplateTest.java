package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.api.modal.DataSourceTableColumn;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.plugin.datasource.kingbase.metadata.KingbaseCatalog;
import org.apache.seatunnel.plugin.datasource.mysql.metadata.MySQLCatalog;
import org.apache.seatunnel.web.api.service.DataSourceService;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataSourceCatalogSqlTemplateTest {

    @Test
    void usesSelectedSchemaForBothColumnLookupAndGeneratedSql() throws Exception {
        DataSourceCatalogServiceImpl service = new DataSourceCatalogServiceImpl();
        DataSourceService dataSourceService = mock(DataSourceService.class);
        ReflectionTestUtils.setField(service, "dataSourceService", dataSourceService);
        DataSource dataSource = mock(DataSource.class);
        when(dataSourceService.selectById(42L)).thenReturn(dataSource);
        when(dataSource.getDbType()).thenReturn(DbType.KINGBASE);
        when(dataSource.getConnectionParams()).thenReturn("{}");

        BaseConnectionParam param = new BaseConnectionParam() {};
        param.setDatabase("connection_db");
        param.setSchemaName("connection_schema");
        KingbaseCatalog catalog = spy(new KingbaseCatalog(param, null));
        DataSourceTableColumn column = DataSourceTableColumn.builder().columnName("order_id").build();
        doAnswer(invocation -> {
            Map<String, Object> request = invocation.getArgument(0);
            assertEquals("orders_db", request.get("database"));
            assertEquals("sales", request.get("schema_name"));
            return List.of(column);
        }).when(catalog).listColumns(anyMap());
        DataSourceProcessor processor = mock(DataSourceProcessor.class);
        when(processor.getCatalog(param)).thenReturn(Optional.of(catalog));

        try (MockedStatic<DataSourceUtils> utils = mockStatic(DataSourceUtils.class)) {
            utils.when(() -> DataSourceUtils.buildConnectionParams(DbType.KINGBASE, "{}")).thenReturn(param);
            utils.when(() -> DataSourceUtils.getDatasourceProcessor(DbType.KINGBASE)).thenReturn(processor);

            String sql = service.buildSqlTemplate(42L, Map.of(
                    "read_mode", "table", "table_path", "orders",
                    "database", "orders_db", "schema_name", "sales"));

            assertEquals("SELECT order_id\nFROM sales.orders", sql);
        }
    }

    @Test
    void mysqlTableListingStaysWithinSelectedDatabaseWhenMetadataWouldBeEmpty() throws Exception {
        BaseConnectionParam param = new BaseConnectionParam() {};
        param.setDatabase("seatunnel_web");
        JdbcConnectionProvider provider = mock(JdbcConnectionProvider.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(provider.getConnection(param)).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getString("TABLE_NAME")).thenReturn("accounts");
        when(result.getString("TABLE_COMMENT")).thenReturn("");

        MySQLCatalog catalog = new MySQLCatalog(param, provider);
        List<OptionVO> options = catalog.listTableOptions("performance_schema");

        assertEquals(List.of("accounts"), options.stream().map(OptionVO::getValue).toList());
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("TABLE_SCHEMA = 'performance_schema'"));
        verify(connection).close();
    }
}
