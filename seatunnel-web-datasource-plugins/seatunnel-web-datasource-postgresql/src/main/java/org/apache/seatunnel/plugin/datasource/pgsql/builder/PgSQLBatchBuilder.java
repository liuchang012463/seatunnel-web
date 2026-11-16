package org.apache.seatunnel.plugin.datasource.pgsql.builder;

import com.google.auto.service.AutoService;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigValueFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.constants.DataSourceConstants;
import org.apache.seatunnel.plugin.datasource.api.hocon.AbstractJdbcBatchBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.api.hocon.JdbcBatchConstants;
import org.apache.seatunnel.plugin.datasource.api.hocon.table.JdbcSourceTargetBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.table.JdbcTableNameResolver;

import java.util.Map;

@AutoService(DataSourceHoconBuilder.class)
@Slf4j
public class PgSQLBatchBuilder extends AbstractJdbcBatchBuilder {

    private final JdbcSourceTargetBuilder pgSingleSourceBuilder;
    private final JdbcSourceTargetBuilder pgMultiSourceBuilder;
//    private final JdbcSinkTargetBuilder pgSingleSinkBuilder;

    public PgSQLBatchBuilder() {
        super();

        JdbcTableNameResolver tableNameResolver = new JdbcTableNameResolver();

        // PostgreSQL-specific source builders (already handled by parent)
        this.pgSingleSourceBuilder = null; // Use parent's
        this.pgMultiSourceBuilder = null;  // Use parent's

        // PostgreSQL-specific sink builder with schema.table format
//        this.pgSingleSinkBuilder = new PgSQLSingleSinkTargetBuilder(tableNameResolver);
    }

    @Override
    protected String defaultDriver() {
        return DataSourceConstants.ORG_POSTGRESQL_DRIVER;
    }

    @Override
    public Config buildSinkHocon(HoconBuildContext context) {
        Config sink = super.buildSinkHocon(context);
        if (!sink.getBoolean(JdbcBatchConstants.GENERATE_SINK_SQL)
                || sink.hasPath(JdbcBatchConstants.QUERY)
                || sink.hasPath("properties.stringtype")) {
            return sink;
        }

        String url = sink.getString(JdbcBatchConstants.URL);
        String urlOptions = StringUtils.substringAfter(url, "?");
        if (("&" + urlOptions).contains("&stringtype=")) {
            return sink;
        }

        // SeaTunnel binds CSV strings with setString; let PostgreSQL infer JSON/JSONB from the target column.
        String separator = url.contains("?") ? "&" : "?";
        log.debug("Using stringtype=unspecified for generated PostgreSQL sink SQL");
        return sink.withValue(JdbcBatchConstants.URL,
                ConfigValueFactory.fromAnyRef(url + separator + "stringtype=unspecified"));
    }

    @Override
    protected String buildTablePath(String database, String schemaName, String table) {
        // PostgreSQL requires schema in table path: database.schema.table
        // If schema is not provided, use default 'public' schema
        String schema = StringUtils.isNotBlank(schemaName) ? schemaName : "public";
        return String.format("%s.%s.%s", database, schema, table);
    }

//    @Override
//    public Config buildSinkHocon(String connectionParam, Config config) {
//        Config conn = ConfigFactory.parseString(connectionParam);
//        Map<String, Object> map = new HashMap<>(32);
//
//        putConnCommon(conn, map);
//
//        // Use PostgreSQL-specific sink builder
////        pgSingleSinkBuilder.build(config, conn, map);
//
//        // Append common options
//        appendSinkOptions(config, map);
//
//        return ConfigFactory.parseMap(map);
//    }

    private void appendSinkOptions(Config config, Map<String, Object> map) {
        // Add common sink options
        if (!map.containsKey(JdbcBatchConstants.GENERATE_SINK_SQL)) {
            map.put(JdbcBatchConstants.GENERATE_SINK_SQL, true);
        }
        if (!map.containsKey(JdbcBatchConstants.BATCH_SIZE)) {
            map.put(JdbcBatchConstants.BATCH_SIZE, 1000);
        }
    }

    @Override
    public String pluginName() {
        return "JDBC-POSTGRESQL";
    }
}
