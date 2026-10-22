package org.apache.seatunnel.web.api.metadata.openlineage;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the source and sink plugins out of a submitted SeaTunnel job configuration.
 *
 * <p>
 * This is a pure parser over the job's own configuration: it performs no OpenMetadata I/O and
 * shares nothing with the OpenMetadata lineage request JSON. It answers the two questions the
 * OpenLineage bridge needs for complex lineage: which tables the job reads, and which tables it
 * writes.
 * </p>
 *
 * <p>
 * The parser accepts every shape the web submits:
 * </p>
 * <ul>
 *   <li>{@code source { Jdbc { table_path = "db.t" } }} — one plugin per child object;</li>
 *   <li>{@code source { plugin_name = "Jdbc", table_path = "db.t" }} — one plugin per block;</li>
 *   <li>{@code source = [ { Jdbc { ... } }, { Kafka { ... } } ]} — one plugin per list element;</li>
 *   <li>multi table jobs — {@code table_list} / {@code source_table_list} / {@code sink_table_list}.</li>
 * </ul>
 */
public final class OpenLineageJobTopology {

    private static final String SECTION_SOURCE = "source";
    private static final String SECTION_SINK = "sink";

    /** Keys that mark a block as one plugin instead of a container of plugins. */
    private static final List<String> PLUGIN_MARKERS = List.of("plugin_name", "factory", "type");
    /** Keys that may carry a table list, in the order they are read. */
    private static final List<String> TABLE_LIST_KEYS =
            List.of("table_list", "source_table_list", "sink_table_list");
    /** Keys that may carry the local datasource id of the plugin. */
    private static final List<String> DATASOURCE_ID_KEYS =
            List.of("dataSourceId", "datasourceId", "data_source_id");

    private OpenLineageJobTopology() {}

    /**
     * The source and sink plugins of one submitted job configuration.
     *
     * @param sources source plugins in configuration order; empty when the config cannot be read
     * @param sinks sink plugins in configuration order; empty when the config cannot be read
     */
    public record Topology(List<Plugin> sources, List<Plugin> sinks) {
    }

    /**
     * One source or sink plugin.
     *
     * @param configPath path of the plugin inside the job configuration, e.g. {@code source.Jdbc}
     * @param tables concrete tables the plugin reads or writes; empty when only a query is given
     * @param datasourceId local datasource id when the configuration names one
     * @param url connection url of the plugin, used to resolve the datasource when it is unnamed
     * @param username connection user of the plugin, used to disambiguate the datasource
     */
    public record Plugin(
            String configPath,
            List<String> tables,
            Long datasourceId,
            String url,
            String username) {
    }

    /**
     * Parses the source and sink plugins of a job configuration. A missing or unreadable section
     * yields an empty list rather than an error: the caller then falls back to the instance
     * columns.
     */
    public static Topology parse(Config runtimeConfig) {
        if (runtimeConfig == null) {
            return new Topology(List.of(), List.of());
        }
        return new Topology(
                plugins(runtimeConfig, SECTION_SOURCE),
                plugins(runtimeConfig, SECTION_SINK));
    }

    private static List<Plugin> plugins(Config runtimeConfig, String section) {
        List<Plugin> plugins = new ArrayList<>();
        if (!runtimeConfig.hasPath(section)) {
            return plugins;
        }
        List<Config> blocks = new ArrayList<>();
        boolean listForm = false;
        try {
            ConfigValue value = runtimeConfig.getValue(section);
            if (value.valueType() == ConfigValueType.LIST) {
                listForm = true;
                for (Config element : runtimeConfig.getConfigList(section)) {
                    blocks.add(element);
                }
            } else if (value.valueType() == ConfigValueType.OBJECT) {
                blocks.add(runtimeConfig.getConfig(section));
            }
        } catch (ConfigException error) {
            return plugins;
        }
        int index = 0;
        for (Config block : blocks) {
            String prefix = listForm ? section + "[" + index + "]" : section;
            plugins.addAll(pluginsOf(block, prefix));
            index++;
        }
        return plugins;
    }

    /** One plugin per block, or one plugin per child object when the block is a container. */
    private static List<Plugin> pluginsOf(Config block, String prefix) {
        List<Plugin> plugins = new ArrayList<>();
        if (block == null) {
            return plugins;
        }
        if (isPluginBlock(block)) {
            plugins.add(plugin(block, prefix));
            return plugins;
        }
        for (Map.Entry<String, ConfigValue> entry : block.root().entrySet()) {
            if (entry.getValue().valueType() != ConfigValueType.OBJECT) {
                continue;
            }
            try {
                plugins.add(plugin(block.getConfig(entry.getKey()), prefix + "." + entry.getKey()));
            } catch (ConfigException ignored) {
                // A child that is not a configuration object cannot be a plugin.
            }
        }
        return plugins;
    }

    private static boolean isPluginBlock(Config block) {
        for (String marker : PLUGIN_MARKERS) {
            if (block.hasPath(marker)) {
                return true;
            }
        }
        return false;
    }

    private static Plugin plugin(Config config, String configPath) {
        return new Plugin(
                configPath,
                tables(config),
                datasourceId(config),
                string(config, "url"),
                string(config, "username"));
    }

    /** The concrete tables of one plugin; dynamic placeholders are not tables. */
    private static List<String> tables(Config plugin) {
        Set<String> tables = new LinkedHashSet<>();
        for (String key : TABLE_LIST_KEYS) {
            addTableList(tables, plugin, key);
        }
        if (tables.isEmpty()) {
            String path = string(plugin, "table_path");
            if (path == null) {
                path = string(plugin, "targetTableName");
            }
            if (path == null) {
                String tableName = string(plugin, "table_name");
                if (tableName == null) {
                    tableName = string(plugin, "table");
                }
                path = tableName == null ? null : qualify(plugin, tableName);
            }
            addTable(tables, path);
        }
        return new ArrayList<>(tables);
    }

    private static void addTableList(Set<String> tables, Config plugin, String key) {
        if (!plugin.hasPath(key)) {
            return;
        }
        ConfigValue value = plugin.getValue(key);
        if (value.valueType() != ConfigValueType.LIST) {
            return;
        }
        for (ConfigValue element : (ConfigList) value) {
            if (element.valueType() == ConfigValueType.STRING) {
                addTable(tables, element.unwrapped().toString());
            } else if (element.valueType() == ConfigValueType.OBJECT) {
                addTable(tables, tableOf((ConfigObject) element));
            }
        }
    }

    private static String tableOf(ConfigObject object) {
        for (String field : List.of("table", "table_path", "table_name")) {
            ConfigValue value = object.get(field);
            if (value != null && value.valueType() == ConfigValueType.STRING) {
                return value.unwrapped().toString();
            }
        }
        return null;
    }

    /** Joins the catalog and database of a plugin with its table name when they are separate. */
    private static String qualify(Config plugin, String tableName) {
        if (tableName.contains(".")) {
            return tableName;
        }
        String catalog = string(plugin, "catalog_name");
        String database = string(plugin, "database_name");
        if (database == null) {
            database = string(plugin, "database");
        }
        if (catalog != null && database != null) {
            return catalog + "." + database + "." + tableName;
        }
        if (database != null) {
            return database + "." + tableName;
        }
        return tableName;
    }

    private static void addTable(Set<String> tables, String table) {
        String value = StringUtils.trimToNull(table);
        if (value == null || value.contains("${")) {
            return;
        }
        tables.add(value);
    }

    private static Long datasourceId(Config plugin) {
        for (String key : DATASOURCE_ID_KEYS) {
            String value = string(plugin, key);
            if (value == null) {
                continue;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                // A non numeric id cannot identify a datasource.
            }
        }
        return null;
    }

    private static String string(Config config, String key) {
        if (!config.hasPath(key)) {
            return null;
        }
        try {
            ConfigValue value = config.getValue(key);
            if (value.valueType() == ConfigValueType.STRING) {
                return StringUtils.trimToNull(value.unwrapped().toString());
            }
            if (value.valueType() == ConfigValueType.NUMBER) {
                return StringUtils.trimToNull(value.unwrapped().toString());
            }
        } catch (ConfigException ignored) {
            // An unreadable value is treated as absent.
        }
        return null;
    }
}
