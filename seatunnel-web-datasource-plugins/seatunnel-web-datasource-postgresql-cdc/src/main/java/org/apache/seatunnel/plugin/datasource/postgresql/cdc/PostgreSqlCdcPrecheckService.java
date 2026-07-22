package org.apache.seatunnel.plugin.datasource.postgresql.cdc;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.plugin.datasource.api.cdc.CdcDatasourcePrecheckItem;
import org.apache.seatunnel.plugin.datasource.api.cdc.CdcDatasourcePrecheckResult;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** Datasource-level, read-only PostgreSQL CDC prerequisites. */
@Slf4j
public class PostgreSqlCdcPrecheckService {

    public CdcDatasourcePrecheckResult check(String connectionParams) {
        CdcDatasourcePrecheckResult result = new CdcDatasourcePrecheckResult();
        try {
            BaseConnectionParam param =
                    DataSourceUtils.buildJdbcConnectionParams(DbType.POSTGRE_SQL, connectionParams);
            DataSourceProcessor processor = DataSourceUtils.getDatasourceProcessor(DbType.POSTGRE_SQL);
            JdbcConnectionProvider provider = processor.getConnectionManager();

            try (Connection connection = provider.getConnection(param)) {
                result.addItem(checkWalLevel(connection));
                result.addItem(checkReplicationRole(connection));
            }
        } catch (Exception e) {
            log.warn("Open PostgreSQL CDC precheck connection failed", e);
            result.addItem(CdcDatasourcePrecheckItem.fail(
                    "POSTGRES_CDC_DIRECT_CONNECTIVITY", "数据库直连", e.getMessage(), "连接成功",
                    "无法连接 PostgreSQL 执行 CDC 前置检查"));
        }
        result.refreshSuccess();
        return result;
    }

    private CdcDatasourcePrecheckItem checkWalLevel(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SHOW wal_level");
             ResultSet rs = statement.executeQuery()) {
            String value = rs.next() ? rs.getString(1) : null;
            if ("logical".equalsIgnoreCase(value)) {
                return CdcDatasourcePrecheckItem.success(
                        "POSTGRES_CDC_WAL_LEVEL", "WAL 逻辑复制", value, "logical", "PostgreSQL 已开启逻辑 WAL");
            }
            return CdcDatasourcePrecheckItem.fail(
                    "POSTGRES_CDC_WAL_LEVEL", "WAL 逻辑复制", value, "logical",
                    "请设置 wal_level=logical 并重启或重载 PostgreSQL");
        }
    }

    private CdcDatasourcePrecheckItem checkReplicationRole(Connection connection) throws Exception {
        String sql = "SELECT rolreplication AND rolcanlogin FROM pg_roles WHERE rolname = current_user";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            boolean enabled = rs.next() && rs.getBoolean(1);
            if (enabled) {
                return CdcDatasourcePrecheckItem.success(
                        "POSTGRES_CDC_REPLICATION_ROLE", "复制账号权限", "REPLICATION LOGIN",
                        "REPLICATION LOGIN", "当前用户具备逻辑复制权限");
            }
            return CdcDatasourcePrecheckItem.fail(
                    "POSTGRES_CDC_REPLICATION_ROLE", "复制账号权限", "缺少 REPLICATION LOGIN",
                    "REPLICATION LOGIN", "请为 CDC 用户授予 REPLICATION LOGIN 权限");
        }
    }
}
