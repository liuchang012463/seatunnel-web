package org.apache.seatunnel.plugin.datasource.dameng.builder;

import com.google.auto.service.AutoService;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigValueFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.constants.DataSourceConstants;
import org.apache.seatunnel.plugin.datasource.api.hocon.AbstractJdbcBatchBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.dameng.connection.DamengConnectionProvider;
import org.apache.seatunnel.plugin.datasource.dameng.param.DamengConnectionParam;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

@Slf4j
@AutoService(DataSourceHoconBuilder.class)
public class DamengBatchBuilder extends AbstractJdbcBatchBuilder {

    private static final String DAMENG_DIALECT = "Dameng";
    private static final String DEFAULT_DRIVER_JAR = "DmJdbcDriver18-8.1.2.141.jar";
    private static final String INSTANCE_NAME_SQL = "SELECT name FROM v$database";

    @Override
    protected String defaultDriver() {
        return DataSourceConstants.COM_DAMENG_JDBC_DRIVER;
    }

    @Override
    public Config buildSourceHocon(HoconBuildContext context) {
        return super.buildSourceHocon(normalizeContext(context));
    }

    @Override
    public Config buildSinkHocon(HoconBuildContext context) {
        Config config = super.buildSinkHocon(normalizeContext(context));

        if (!config.hasPath("dialect")) {
            config =
                    config.withValue(
                            "dialect", ConfigValueFactory.fromAnyRef(DAMENG_DIALECT));
        }

        // SeaTunnel Engine 2.3.13 DamengCreateTableSqlBuilder concatenates
        // CREATE TABLE + COMMENT ON COLUMN into one statement; Dameng JDBC
        // rejects that (issue #10933). Keep auto-create enabled so a patched
        // Engine can create tables; comments are still best-effort there.

        return config;
    }

    /**
     * Source and sink must use the same live database instance name.  The
     * connection form can contain a legacy URL/database value, so normalize
     * both connection and node config before the generic JDBC builders create
     * table paths.
     */
    private HoconBuildContext normalizeContext(HoconBuildContext context) {
        if (context == null || context.getConnectionConfig() == null) {
            return context;
        }

        Config connection = context.getConnectionConfig();
        String configured = connection.hasPath("database")
                ? StringUtils.trimToEmpty(connection.getString("database"))
                : "";
        String physicalName = resolvePhysicalDatabaseName(context, configured);
        if (StringUtils.isBlank(physicalName)) {
            return context;
        }

        Config normalizedConnection = connection.withValue(
                "database", ConfigValueFactory.fromAnyRef(physicalName));

        Config node = context.getNodeConfig();
        if (node != null) {
            node = node.withValue(
                    "database", ConfigValueFactory.fromAnyRef(physicalName));
        }

        return HoconBuildContext.builder()
                .connectionParam(context.getConnectionParam())
                .connectionConfig(normalizedConnection)
                .nodeConfig(node)
                .stage(context.getStage())
                .scheduleConfig(context.getScheduleConfig())
                .hasTransform(context.isHasTransform())
                .dataSourceId(context.getDataSourceId())
                .dataSourceName(context.getDataSourceName())
                .dbType(context.getDbType())
                .build();
    }

    /**
     * Prefer the live Dameng instance name; fall back to uppercasing the configured
     * value (Dameng stores unquoted identifiers in uppercase, commonly {@code DAMENG}).
     */
    private String resolvePhysicalDatabaseName(HoconBuildContext context, String configured) {
        String fromInstance = queryInstanceName(context);
        if (StringUtils.isNotBlank(fromInstance)) {
            return fromInstance.trim();
        }
        if (StringUtils.isBlank(configured)) {
            return configured;
        }
        return configured.trim().toUpperCase(Locale.ROOT);
    }

    private String queryInstanceName(HoconBuildContext context) {
        if (context == null || context.getConnectionConfig() == null) {
            return null;
        }

        Config conn = context.getConnectionConfig();
        String url = stringOrNull(conn, "url");
        String user = firstNonBlank(stringOrNull(conn, "user"), stringOrNull(conn, "username"));
        if (StringUtils.isBlank(url) || StringUtils.isBlank(user)) {
            return null;
        }

        DamengConnectionParam param = new DamengConnectionParam();
        param.setUrl(url);
        param.setUser(user);
        param.setPassword(StringUtils.defaultString(stringOrNull(conn, "password")));
        param.setDriver(
                StringUtils.defaultIfBlank(
                        stringOrNull(conn, "driver"),
                        DataSourceConstants.COM_DAMENG_JDBC_DRIVER));
        param.setDriverLocation(
                StringUtils.defaultIfBlank(
                        stringOrNull(conn, "driverLocation"), DEFAULT_DRIVER_JAR));

        try {
            DamengConnectionProvider provider = new DamengConnectionProvider();
            try (Connection connection = provider.getConnection(param);
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery(INSTANCE_NAME_SQL)) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (Exception e) {
            log.debug(
                    "Unable to resolve Dameng instance name from v$database; "
                            + "falling back to uppercased datasource database field",
                    e);
        }
        return null;
    }

    private static String stringOrNull(Config config, String path) {
        if (config == null || !config.hasPath(path)) {
            return null;
        }
        return config.getString(path);
    }

    private static String firstNonBlank(String first, String second) {
        if (StringUtils.isNotBlank(first)) {
            return first;
        }
        return second;
    }

    @Override
    public String pluginName() {
        return "JDBC-DAMENG";
    }
}
