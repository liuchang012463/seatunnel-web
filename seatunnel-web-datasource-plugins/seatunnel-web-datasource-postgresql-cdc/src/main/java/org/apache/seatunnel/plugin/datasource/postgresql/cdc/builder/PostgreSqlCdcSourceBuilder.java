package org.apache.seatunnel.plugin.datasource.postgresql.cdc.builder;

import com.google.auto.service.AutoService;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValueFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.api.hocon.cdc.AbstractCdcSourceBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builder for SeaTunnel 2.3.13 {@code Postgres-CDC}. */
@AutoService(DataSourceHoconBuilder.class)
public class PostgreSqlCdcSourceBuilder extends AbstractCdcSourceBuilder {

    private static final String SCHEMA_NAMES = "schema-names";
    private static final String SLOT_NAME = "slot.name";
    private static final String DECODING_PLUGIN_NAME = "decoding.plugin.name";
    private static final String PUBLICATION_NAME = "publicationName";

    @Override
    public String pluginName() {
        return "POSTGRESQL-CDC";
    }

    @Override
    public Config buildSourceHocon(HoconBuildContext context) {
        if (context.getNodeConfig().hasPath(TABLE_PATTERN)
                || context.getNodeConfig().hasPath(DATABASE_PATTERN)) {
            throw new IllegalArgumentException("PostgreSQL CDC only supports exact table selection");
        }

        Config base = super.buildSourceHocon(context);
        Map<String, Object> options = new LinkedHashMap<>(base.root().unwrapped());
        // server-id is a MySQL CDC setting. It can be present in generic guide data
        // from older tasks, but it is not a SeaTunnel PostgreSQL CDC option.
        options.remove("server-id");
        options.remove("serverId");
        options.remove("serverIdMode");
        List<String> tables = normalizeTableNames(options.get(TABLE_NAMES), context.getConnectionConfig());
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("PostgreSQL CDC requires at least one table");
        }

        String slotName = getString(context.getNodeConfig(), SLOT_NAME);
        if (StringUtils.isBlank(slotName)) {
            slotName = getString(context.getNodeConfig(), "slotName");
        }
        if (StringUtils.isBlank(slotName)) {
            throw new IllegalArgumentException("PostgreSQL CDC requires slot.name");
        }

        String publicationName = getString(context.getNodeConfig(), PUBLICATION_NAME);
        if (StringUtils.isBlank(publicationName)) {
            throw new IllegalArgumentException("PostgreSQL CDC requires publicationName");
        }

        options.put(TABLE_NAMES, tables);
        options.put(SCHEMA_NAMES, schemas(tables));
        options.put(SLOT_NAME, slotName.trim());
        options.put(DECODING_PLUGIN_NAME, "pgoutput");

        Map<String, String> debezium = new LinkedHashMap<>();
        flattenDebeziumOptions(debezium, options.remove(DEBEZIUM), "");
        debezium.put("publication.name", publicationName.trim());
        debezium.put("publication.autocreate.mode", "disabled");

        String startupMode = base.hasPath(STARTUP_MODE) ? base.getString(STARTUP_MODE) : null;
        if (!("initial".equalsIgnoreCase(startupMode)
                || "earliest".equalsIgnoreCase(startupMode)
                || "latest".equalsIgnoreCase(startupMode))) {
            throw new IllegalArgumentException(
                    "PostgreSQL CDC startup.mode must be initial, earliest, or latest");
        }

        Config sourceConfig = ConfigFactory.parseMap(options);
        ConfigObject debeziumConfig = ConfigFactory.empty().root();
        for (Map.Entry<String, String> entry : debezium.entrySet()) {
            // ConfigFactory.parseMap interprets dots in map keys as paths. Debezium expects
            // Map<String, String>, so retain dotted keys as literal object keys instead.
            debeziumConfig = debeziumConfig.withValue(
                    entry.getKey(), ConfigValueFactory.fromAnyRef(entry.getValue()));
        }
        return sourceConfig.withValue(DEBEZIUM, debeziumConfig);
    }

    @Override
    public String sourceTemplate() {
        return ""
                + "  Postgres-CDC {\n"
                + "    datasourceId = @\n"
                + "    table = \"public.orders\"\n"
                + "    slot.name = \"seatunnel_orders\"\n"
                + "    publicationName = \"seatunnel_orders_pub\"\n"
                + "    startup.mode = \"initial\"\n"
                + "  }\n";
    }

    private List<String> normalizeTableNames(Object rawTables, Config connection) {
        if (!(rawTables instanceof List)) {
            return Collections.emptyList();
        }

        String database = getString(connection, DATABASE);
        String defaultSchema = StringUtils.defaultIfBlank(getString(connection, "schemaName"), "public");
        List<String> result = new ArrayList<>();

        for (Object raw : (List<?>) rawTables) {
            String table = stringValue(raw);
            if (StringUtils.isBlank(table)) {
                continue;
            }
            String[] parts = table.trim().split("\\.");
            if (parts.length == 1) {
                result.add(database + "." + defaultSchema + "." + parts[0]);
            } else if (parts.length == 2) {
                // The shared single-table resolver has already prefixed a bare table
                // with the database. Retain its schema from the datasource instead
                // of interpreting that database as a schema for a second time.
                if (StringUtils.equals(parts[0], database)) {
                    result.add(database + "." + defaultSchema + "." + parts[1]);
                } else {
                    result.add(database + "." + parts[0] + "." + parts[1]);
                }
            } else if (parts.length == 3) {
                result.add(table.trim());
            } else {
                throw new IllegalArgumentException("Invalid PostgreSQL CDC table name: " + table);
            }
        }
        return result;
    }

    private List<String> schemas(List<String> tables) {
        Set<String> schemas = new LinkedHashSet<>();
        for (String table : tables) {
            String[] parts = table.split("\\.");
            if (parts.length == 3) {
                schemas.add(parts[1]);
            }
        }
        return new ArrayList<>(schemas);
    }

    private void flattenDebeziumOptions(Map<String, String> flattened, Object value, String prefix) {
        if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = String.valueOf(entry.getKey());
                String path = StringUtils.isBlank(prefix) ? key : prefix + "." + key;
                flattenDebeziumOptions(flattened, entry.getValue(), path);
            }
            return;
        }
        if (value != null && StringUtils.isNotBlank(prefix)) {
            flattened.put(prefix, String.valueOf(value));
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
