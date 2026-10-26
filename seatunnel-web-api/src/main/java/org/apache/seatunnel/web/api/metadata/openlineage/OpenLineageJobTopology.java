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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the source and sink plugins out of a submitted SeaTunnel job configuration.
 *
 * <p>
 * This is a pure parser over the job's own configuration: it performs no OpenMetadata I/O. It
 * answers the two questions the OpenLineage bridge needs for complex lineage: which tables the
 * job reads, and which tables it writes.
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
    private static final List<String> PLUGIN_MARKERS = List.of("plugin_name", "factory");
    /** Keys that may carry a table list, in the order they are read. */
    private static final List<String> TABLE_LIST_KEYS =
            List.of("table_list", "source_table_list", "sink_table_list", "table-names", "table_names");
    /**
     * Message-broker dataset keys. A Kafka/Mqtt/Pulsar topic is the dataset of the plugin and is
     * modeled like a table path: the topic name under the datasource namespace.
     */
    private static final List<String> TOPIC_LIST_KEYS = List.of("topic", "topics");
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
     * @param inputName plugin output consumed by this plugin, the identity a transform uses
     * @param outputName plugin output this plugin produces, the identity a transform reads
     * @param pattern the plugin subscribes by regular expression (Kafka pattern=true), so its
     *     topic option is not a dataset name
     */
    public record Plugin(
            String configPath,
            List<String> tables,
            Long datasourceId,
            String url,
            String username,
            String inputName,
            String outputName,
            boolean pattern) {
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
        // "type" is also a common field of a plugin, but a container may carry one too. It only
        // identifies a plugin when the block has no child configuration objects to descend into.
        if (block.hasPath("type")) {
            for (Map.Entry<String, ConfigValue> entry : block.root().entrySet()) {
                if (entry.getValue().valueType() == ConfigValueType.OBJECT) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static Plugin plugin(Config config, String configPath) {
        boolean pattern = patternSubscription(config);
        return new Plugin(
                configPath,
                tables(config, pattern),
                datasourceId(config),
                // A message-broker plugin has no url: its connection is the bootstrap servers,
                // which is how the local datasource stores it too.
                firstNonBlank(string(config, "url"), string(config, "bootstrap.servers")),
                string(config, "username"),
                firstNonBlank(string(config, "plugin_input"), string(config, "source_table_name")),
                firstNonBlank(string(config, "plugin_output"), string(config, "result_table_name")),
                pattern);
    }

    /** The concrete tables of one plugin; dynamic placeholders are not tables. */
    private static List<String> tables(Config plugin, boolean pattern) {
        if (pattern) {
            // A pattern subscription names a regex, never a concrete dataset: no table or topic
            // option of such a plugin may be published.
            return new ArrayList<>();
        }
        Set<String> tables = new LinkedHashSet<>();
        for (String key : TABLE_LIST_KEYS) {
            addTableList(tables, plugin, key);
        }
        for (String key : TOPIC_LIST_KEYS) {
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
            } else {
                // The same table configured through table_path and through table_name has to
                // carry the same identity, otherwise the asset side resolves two datasets for
                // one physical table.
                path = qualify(plugin, path);
            }
            addTable(tables, path);
        }
        if (tables.isEmpty()) {
            // A query-only connector names no table option, so its FROM clause is the only source
            // of concrete tables. Every top-level FROM/JOIN table is read; a subquery, CTE or
            // dynamic table is not a table and stays absent.
            String query = firstNonBlank(string(plugin, "query"), string(plugin, "sql"));
            tables.addAll(tablesFromQuery(query));
        }
        return new ArrayList<>(tables);
    }

    private static void addTableList(Set<String> tables, Config plugin, String key) {
        if (!plugin.hasPath(key)) {
            return;
        }
        ConfigValue value = plugin.getValue(key);
        if (value.valueType() == ConfigValueType.STRING) {
            addTableNames(tables, value.unwrapped().toString());
            return;
        }
        if (value.valueType() != ConfigValueType.LIST) {
            return;
        }
        for (ConfigValue element : (ConfigList) value) {
            if (element.valueType() == ConfigValueType.STRING) {
                addTableNames(tables, element.unwrapped().toString());
            } else if (element.valueType() == ConfigValueType.OBJECT) {
                addTable(tables, tableOf((ConfigObject) element));
            }
        }
    }

    /** A table list submitted as one comma separated string. */
    static void addTableNames(Set<String> tables, String raw) {
        String value = StringUtils.trimToNull(raw);
        if (value == null) {
            return;
        }
        if (!value.contains(",")) {
            addTable(tables, value);
            return;
        }
        for (String table : value.split(",")) {
            addTable(tables, table);
        }
    }

    static String tableOf(ConfigObject object) {
        String table = null;
        for (String field : List.of("table", "table_path", "table_name")) {
            ConfigValue value = object.get(field);
            if (value != null && value.valueType() == ConfigValueType.STRING) {
                table = StringUtils.trimToNull(value.unwrapped().toString());
                if (table != null) {
                    break;
                }
            }
        }
        if (table == null || table.contains(".")) {
            return table;
        }
        String database = string(object, "database");
        String schema = firstNonBlank(string(object, "schema"), string(object, "schema_name"));
        if (database != null && schema != null) {
            return database + "." + schema + "." + table;
        }
        if (database != null) {
            return database + "." + table;
        }
        return schema == null ? table : schema + "." + table;
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

    /**
     * Accepts one candidate table path. A dynamic placeholder, a function call or any multi-word
     * text (a raw query is stored as the table of a script job) is not a table; a hyphen is a
     * legal part of topic and table names, and an explicitly quoted name is taken verbatim.
     */
    static void addTable(Set<String> tables, String table) {
        String trimmed = StringUtils.trimToNull(table);
        boolean quoted = trimmed != null && trimmed.length() >= 2
                && (trimmed.charAt(0) == '`' || trimmed.charAt(0) == '"')
                && trimmed.charAt(trimmed.length() - 1) == trimmed.charAt(0);
        String value = stripQuotes(trimmed);
        if (value == null
                || value.contains("${")
                || value.contains("(")
                || value.contains("*")
                || (!quoted && (value.chars().anyMatch(Character::isWhitespace)
                        || containsRegexMetacharacter(value)))) {
            return;
        }
        tables.add(value);
    }

    /**
     * Regex metacharacters a concrete topic or table name never carries unquoted. A legacy
     * subscription expression ({@code orders-.+}) must not be published as a dataset name.
     */
    private static boolean containsRegexMetacharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '+' || current == '[' || current == ']' || current == '{'
                    || current == '}' || current == '^' || current == '|'
                    || current == '\\' || current == '?') {
                return true;
            }
        }
        return false;
    }

    /**
     * The top-level tables of a query's {@code FROM} clause, including every JOIN and comma
     * separated table. Only plain table references are read: a CTE, a subquery, a function or a
     * union cannot be turned into a concrete dataset name, so such a query yields no table.
     */
    static List<String> tablesFromQuery(String query) {
        Set<String> tables = new LinkedHashSet<>();
        String value = stripSqlComments(StringUtils.trimToNull(query));
        if (value == null || startsWithKeyword(value, "with")) {
            return new ArrayList<>();
        }
        int from = indexOfKeyword(value, "from", 0);
        if (from < 0) {
            return new ArrayList<>();
        }
        List<String> tokens = tokenizeSql(value.substring(from + "from".length()));
        boolean expectTable = true;
        int depth = 0;
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if ("(".equals(token)) {
                if (expectTable) {
                    // A derived table is not a physical table; its columns cannot be attributed.
                    return new ArrayList<>();
                }
                depth++;
                continue;
            }
            if (")".equals(token)) {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (";".equals(token)) {
                break;
            }
            if (depth > 0) {
                continue;
            }
            if (",".equals(token)) {
                expectTable = true;
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if ("lateral".equals(lower) || "table".equals(lower)) {
                // LATERAL and TABLE introduce a derived/function source, not a physical table.
                return new ArrayList<>();
            }
            if ("union".equals(lower) || "intersect".equals(lower) || "except".equals(lower)) {
                // A set operation combines several queries; the columns cannot be attributed to
                // the first branch's tables.
                return new ArrayList<>();
            }
            if (isFromTerminator(lower)) {
                break;
            }
            if (isJoinKeyword(lower)) {
                expectTable = true;
                continue;
            }
            if ("on".equals(lower) || "using".equals(lower)) {
                expectTable = false;
                continue;
            }
            if (!expectTable) {
                continue;
            }
            if (index + 1 < tokens.size() && "(".equals(tokens.get(index + 1))) {
                // A table function such as generate_series(...) or unnest(...) is not a table.
                return new ArrayList<>();
            }
            String table = normalizeTableToken(token);
            if (table == null) {
                continue;
            }
            addTable(tables, table);
            expectTable = false;
            int next = index + 1;
            if (next < tokens.size() && "as".equalsIgnoreCase(tokens.get(next))) {
                next++;
            }
            if (next < tokens.size() && isAliasToken(tokens.get(next))) {
                index = next;
            }
        }
        return new ArrayList<>(tables);
    }

    private static boolean isAliasToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return !",".equals(token)
                && !"(".equals(token)
                && !")".equals(token)
                && !";".equals(token)
                && !isFromTerminator(lower)
                && !isJoinKeyword(lower)
                && !"on".equals(lower)
                && !"using".equals(lower)
                && normalizeTableToken(token) != null;
    }

    private static boolean isJoinKeyword(String token) {
        switch (token) {
            case "join":
            case "inner":
            case "left":
            case "right":
            case "full":
            case "cross":
            case "outer":
            case "natural":
            case "straight_join":
                return true;
            default:
                return false;
        }
    }

    private static boolean isFromTerminator(String token) {
        switch (token) {
            case "where":
            case "group":
            case "order":
            case "having":
            case "limit":
            case "offset":
            case "fetch":
            case "union":
            case "intersect":
            case "except":
            case "window":
            case "qualify":
                return true;
            default:
                return false;
        }
    }

    private static List<String> tokenizeSql(String clause) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < clause.length(); index++) {
            char value = clause.charAt(index);
            if (quote != 0) {
                current.append(value);
                if (value == quote) {
                    quote = 0;
                }
                continue;
            }
            if (value == '\'' || value == '"' || value == '`') {
                quote = value;
                current.append(value);
                continue;
            }
            if (Character.isWhitespace(value)
                    || value == ','
                    || value == '('
                    || value == ')'
                    || value == ';') {
                flushToken(tokens, current);
                if (value == ',' || value == '(' || value == ')' || value == ';') {
                    tokens.add(String.valueOf(value));
                }
                continue;
            }
            current.append(value);
        }
        flushToken(tokens, current);
        return tokens;
    }

    private static void flushToken(List<String> tokens, StringBuilder current) {
        if (current.length() > 0) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    /** Removes line and block comments without touching quoted text. */
    private static String stripSqlComments(String query) {
        String value = StringUtils.trimToNull(query);
        if (value == null) {
            return null;
        }
        StringBuilder result = new StringBuilder(value.length());
        boolean lineComment = false;
        boolean blockComment = false;
        char quote = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            char next = index + 1 < value.length() ? value.charAt(index + 1) : 0;
            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                    result.append(current);
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (quote != 0) {
                result.append(current);
                if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                result.append(current);
                continue;
            }
            if (current == '-' && next == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (current == '#' && next != '>') {
                // MySQL line comment. PostgreSQL's #> and #>> operators are not comments.
                lineComment = true;
                continue;
            }
            if (current == '/' && next == '*') {
                blockComment = true;
                index++;
                continue;
            }
            result.append(current);
        }
        return StringUtils.trimToNull(result.toString());
    }

    private static boolean startsWithKeyword(String text, String keyword) {
        String value = text.trim();
        return value.regionMatches(true, 0, keyword, 0, keyword.length())
                && !isIdentifierChar(value, keyword.length());
    }

    /**
     * A table or alias token: a quoted identifier keeps its content verbatim (SeaTunnel generated
     * names may contain hyphens), an unquoted one has to be a plain dotted identifier.
     */
    private static String normalizeTableToken(String token) {
        String trimmed = StringUtils.trimToNull(token);
        if (trimmed == null) {
            return null;
        }
        boolean quoted = trimmed.length() >= 2
                && (trimmed.charAt(0) == '`' || trimmed.charAt(0) == '"')
                && trimmed.charAt(trimmed.length() - 1) == trimmed.charAt(0);
        String value = stripQuotes(trimmed);
        if (StringUtils.isBlank(value)) {
            return null;
        }
        if (!quoted && !value.matches("[A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)*")) {
            return null;
        }
        return value;
    }

    private static int indexOfKeyword(String text, String keyword, int fromIndex) {
        int depth = 0;
        char quote = 0;
        for (int index = fromIndex; index + keyword.length() <= text.length(); index++) {
            char current = text.charAt(index);
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                continue;
            }
            if (current == '(') {
                depth++;
                continue;
            }
            if (current == ')') {
                depth--;
                continue;
            }
            if (depth != 0 || Character.toLowerCase(current) != keyword.charAt(0)) {
                continue;
            }
            if (text.regionMatches(true, index, keyword, 0, keyword.length())
                    && !isIdentifierChar(text, index - 1)
                    && !isIdentifierChar(text, index + keyword.length())) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isIdentifierChar(String text, int index) {
        return index >= 0
                && index < text.length()
                && (Character.isLetterOrDigit(text.charAt(index)) || text.charAt(index) == '_');
    }

    private static String stripQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        if ((first == '`' || first == '"') && value.charAt(value.length() - 1) == first) {
            return StringUtils.trimToNull(value.substring(1, value.length() - 1));
        }
        return value;
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : second;
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

    /**
     * True when the plugin subscribes to topics by regular expression (Kafka pattern=true). Only
     * the Kafka source writes this key today; a connector that ever reuses it with another
     * meaning has to revisit this rule.
     */
    private static boolean patternSubscription(Config plugin) {
        if (!plugin.hasPath("pattern")) {
            return false;
        }
        try {
            ConfigValue value = plugin.getValue("pattern");
            if (value.valueType() == ConfigValueType.BOOLEAN) {
                return Boolean.TRUE.equals(value.unwrapped());
            }
            if (value.valueType() == ConfigValueType.STRING) {
                String text = StringUtils.trimToNull(value.unwrapped().toString());
                if (text == null) {
                    return false;
                }
                if ("true".equalsIgnoreCase(text)) {
                    return true;
                }
                if ("false".equalsIgnoreCase(text)) {
                    return false;
                }
                // Older Web payloads stored the regex expression itself in "pattern"; the
                // current builder migrates such a value to topic with pattern=true. An already
                // stored runtime config keeps the old shape, so it is still a pattern
                // subscription and must not be read as a topic name.
                return true;
            }
        } catch (ConfigException ignored) {
            // An unreadable flag keeps the default: treat the topic as a concrete name.
        }
        return false;
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

    private static String string(ConfigObject config, String key) {
        ConfigValue value = config.get(key);
        if (value != null && value.valueType() == ConfigValueType.STRING) {
            return StringUtils.trimToNull(value.unwrapped().toString());
        }
        return null;
    }
}
