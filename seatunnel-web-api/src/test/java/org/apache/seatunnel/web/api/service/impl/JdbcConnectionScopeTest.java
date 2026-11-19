package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcConnectionScopeTest {

    @ParameterizedTest
    @CsvSource({
        "KINGBASE, orders, orders, true",
        "KINGBASE, ORDERS, orders, false",
        "POSTGRE_SQL, Sales_EU, Sales_EU, true",
        "POSTGRE_SQL, sales_eu, Sales_EU, false",
        "ZEONEDB, missing, orders, false",
        "VASTBASE, sales_eu, salesXeu, false",
        "DAMENG, ORDERS, ORDERS, true",
        "DAMENG, orders, ORDERS, false",
        "JDBC, missing, orders, false"
    })
    void requiresAnExactSchemaName(DbType dbType, String configuredSchema, String actualSchema, boolean matches)
            throws Exception {
        BaseConnectionParam param = DataSourceUtils.buildJdbcConnectionParams(dbType, "{}");
        param.setSchemaName(configuredSchema);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet schemas = mock(ResultSet.class);
        JdbcConnectionProvider provider = spy(DataSourceUtils.getDatasourceProcessor(dbType).getConnectionManager());
        doReturn(connection).when(provider).getConnection(param);
        if (dbType == DbType.DAMENG) {
            param.setDatabase("DAMENG");
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet instance = mock(ResultSet.class);
            when(connection.prepareStatement("SELECT name FROM v$database")).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(instance);
            when(instance.next()).thenReturn(true);
            when(instance.getString(1)).thenReturn("DAMENG");
        }
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getSchemas(null, "%")).thenReturn(schemas);
        when(schemas.next()).thenReturn(true, false);
        when(schemas.getString("TABLE_SCHEM")).thenReturn(actualSchema);

        if (matches) {
            assertTrue(provider.checkDataSourceConnectivity(param));
        } else {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> provider.checkDataSourceConnectivity(param));
            assertTrue(error.getMessage().contains(configuredSchema));
            assertTrue(error.getMessage().contains("注意大小写"));
        }
        verify(schemas).close();
        verify(connection).close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void allowsAnOmittedSchema(String schema) throws Exception {
        BaseConnectionParam param = DataSourceUtils.buildJdbcConnectionParams(DbType.KINGBASE, "{}");
        param.setSchemaName(schema);
        Connection connection = mock(Connection.class);
        JdbcConnectionProvider provider = spy(DataSourceUtils.getDatasourceProcessor(DbType.KINGBASE).getConnectionManager());
        doReturn(connection).when(provider).getConnection(param);

        assertTrue(provider.checkDataSourceConnectivity(param));
        verify(connection, never()).getMetaData();
        verify(connection).close();
    }

    @Test
    void rejectsPostgresJdbcDatabaseThatDiffersFromTheConnectedCatalog() throws Exception {
        BaseConnectionParam param = DataSourceUtils.buildJdbcConnectionParams(DbType.JDBC,
                "{\"url\":\"jdbc:postgresql://db:5432/orders\",\"database\":\"ORDERS\"}");
        Connection connection = mock(Connection.class);
        when(connection.getCatalog()).thenReturn("orders");
        JdbcConnectionProvider provider = spy(DataSourceUtils.getDatasourceProcessor(DbType.JDBC).getConnectionManager());
        doReturn(connection).when(provider).getConnection(param);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> provider.checkDataSourceConnectivity(param));
        assertTrue(error.getMessage().contains("ORDERS"));
        assertTrue(error.getMessage().contains("与 JDBC URL 实际连接的数据库不一致"));
    }
}
