package org.apache.seatunnel.plugin.datasource.jdbc.connection;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractJdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.jdbc.param.JdbcConnectionParam;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

public class JdbcConnectionProviderImpl
        extends AbstractJdbcConnectionProvider<JdbcConnectionParam> {

    @Override
    protected String defaultDriverClass() {
        return "";
    }

    @Override
    protected String resolveDriverLocation(JdbcConnectionParam connectionParam) {
        return connectionParam.getDriverLocation();
    }

    @Override
    protected void validateConnectionScope(Connection connection, JdbcConnectionParam param)
            throws SQLException {
        String catalog = StringUtils.defaultIfBlank(param.getDatabase(), null);
        // PostgreSQL JDBC 只能访问 URL 已连接的数据库，OpenMetadata 会优先使用显式填写的数据库。
        if (catalog != null && param.getUrl().toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:")
                && !catalog.equals(connection.getCatalog())) {
            throw new IllegalArgumentException(
                    "填写的数据库「" + catalog + "」与 JDBC URL 实际连接的数据库不一致，请核对名称和大小写。");
        }
        validateConfiguredSchema(connection, catalog, param.getSchemaName());
    }
}
