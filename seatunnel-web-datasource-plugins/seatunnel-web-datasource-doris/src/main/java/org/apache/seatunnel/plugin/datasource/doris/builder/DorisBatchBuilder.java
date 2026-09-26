package org.apache.seatunnel.plugin.datasource.doris.builder;

import com.google.auto.service.AutoService;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.constants.DataSourceConstants;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractJdbcHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConfigReaders;
import org.apache.seatunnel.plugin.datasource.api.utils.PasswordUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Doris HOCON 构建器。
 *
 * <p>Doris 使用双端口架构：</p>
 * <ul>
 *   <li><b>fenodes</b> (FE HTTP端口, 默认8030) — 用于 StreamLoad 方式读写数据</li>
 *   <li><b>queryPort</b> (MySQL协议端口, 默认9030) — 用于 JDBC 元数据查询</li>
 * </ul>
 *
 * <p>此构建器输出的 HOCON 包含 fenodes 字段，用于 Doris 连接器的 StreamLoad 数据读写。</p>
 */
@AutoService(DataSourceHoconBuilder.class)
public class DorisBatchBuilder extends AbstractJdbcHoconBuilder implements DataSourceHoconBuilder {

    private static final String FENODES = "fenodes";

    @Override
    public String pluginName() {
        return "DORIS";
    }

    @Override
    protected String defaultDriver() {
        return DataSourceConstants.COM_MYSQL_CJ_JDBC_DRIVER;
    }

    @Override
    public Config buildSourceHocon(HoconBuildContext context) {
        Config conn = context.getConnectionConfig();
        Config config = context.getNodeConfig();

        Map<String, Object> map = new HashMap<>(16);

        // Doris 源连接器使用 fenodes (FE HTTP端口) 进行数据读取
        putDorisConnConfig(conn, map);

        if (isMultiTableMode(config)) {
            putMultiTableSourceConfig(config, conn, map);
        } else {
            putSingleTableSourceConfig(config, conn, map);
        }

        // 通用表单字段映射到 Doris 专属参数
        putDorisSourceOptions(config, map);

        return ConfigFactory.parseMap(map);
    }

    @Override
    public Config buildSinkHocon(HoconBuildContext context) {
        Config conn = context.getConnectionConfig();
        Config config = context.getNodeConfig();

        Map<String, Object> map = new HashMap<>(16);

        // Doris sink 使用 fenodes (FE HTTP端口) 通过 StreamLoad 写入数据
        putDorisConnConfig(conn, map);

        if (isMultiTableMode(config)) {
            putMultiTableSinkConfig(config, conn, map);
        } else {
            putTableConfig(config, conn, map);
        }

        putDorisSinkConfig(config, map, context);

        return ConfigFactory.parseMap(map);
    }

    // ======================== Mode Detection ========================

    private boolean isMultiTableMode(Config config) {
        if (JdbcConfigReaders.getBoolean(config, "multiTable", false)) {
            return true;
        }
        List<String> tableList = getTableList(config);
        return tableList.size() > 1;
    }

    private List<String> getTableList(Config config) {
        List<String> result = new ArrayList<>();
        try {
            if (config.hasPath("table_list")) {
                result.addAll(config.getStringList("table_list"));
            }
        } catch (Exception e) {
            // ignore
        }
        if (result.isEmpty()) {
            try {
                if (config.hasPath("source_table_list")) {
                    result.addAll(config.getStringList("source_table_list"));
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return result;
    }

    // ======================== Single Table Source ========================

    /**
     * 单表 Source 配置：database + table + 可选的 doris.filter.query（从 SQL WHERE 提取）。
     */
    private void putSingleTableSourceConfig(Config config, Config conn, Map<String, Object> map) {
        String database = JdbcConfigReaders.getString(config, "database", "");
        if (database.isEmpty()) {
            database = JdbcConfigReaders.getString(conn, "database", "");
        }
        if (!database.isEmpty()) {
            map.put("database", database);
        }

        String table = JdbcConfigReaders.getString(config, "table", "");
        if (!table.isEmpty()) {
            map.put("table", table);
        }

        // 自定义 SQL 模式：提取 WHERE 后的内容作为 doris.filter.query
        String sql = JdbcConfigReaders.getString(config, "sql", "");
        if (StringUtils.isNotBlank(sql)) {
            String filterQuery = extractWhereClause(sql);
            if (StringUtils.isNotBlank(filterQuery)) {
                map.put("doris.filter.query", filterQuery);
            }
            return;
        }        // 按表模式：检查是否有直接的 doris.filter.query
        String filterQuery = JdbcConfigReaders.getString(config, "doris.filter.query", "");
        if (!filterQuery.isEmpty()) {
            map.put("doris.filter.query", filterQuery);
        }
    }

    // ======================== Multi Table Source ========================

    /**
     * 多表 Source 配置：使用 Doris 原生 table_list 格式（每项用 database + table 分离）。
     */
    private void putMultiTableSourceConfig(Config config, Config conn, Map<String, Object> map) {
        String database = JdbcConfigReaders.getString(config, "database", "");
        if (database.isEmpty()) {
            database = JdbcConfigReaders.getString(conn, "database", "");
        }
        if (!database.isEmpty()) {
            map.put("database", database);
        }

        List<String> tables = getTableList(config);
        if (tables.isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing table_list for Doris multi-table source");
        }

        List<Map<String, Object>> tableList = new ArrayList<>();
        for (String table : tables) {
            if (StringUtils.isBlank(table)) {
                continue;
            }
            String tableDatabase = database;
            String tableName = table.trim();
            String[] qualifiedParts = tableName.split("\\.", -1);
            if (qualifiedParts.length == 2
                    && StringUtils.isNotBlank(qualifiedParts[0])
                    && StringUtils.isNotBlank(qualifiedParts[1])) {
                tableDatabase = qualifiedParts[0].trim();
                tableName = qualifiedParts[1].trim();
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("database", tableDatabase);
            item.put("table", tableName);
            tableList.add(item);
        }

        if (!tableList.isEmpty()) {
            map.put("table_list", tableList);
        }
    }

    // ======================== Multi Table Sink ========================

    /**
     * 多表 Sink 配置：使用 ${table_name} 动态表名。
     */
    private void putMultiTableSinkConfig(Config config, Config conn, Map<String, Object> map) {
        String database = JdbcConfigReaders.getString(config, "database", "");
        if (database.isEmpty()) {
            database = JdbcConfigReaders.getString(conn, "database", "");
        }
        if (!database.isEmpty()) {
            map.put("database", database);
        }

        map.put("table", "${table_name}");
    }

    /**
     * 构建 Doris 连接配置，包含 fenodes、username、password。
     */
    private void putDorisConnConfig(Config conn, Map<String, Object> map) {
        // fenodes - FE HTTP地址，用于 StreamLoad 读写
        String fenodes = JdbcConfigReaders.getString(conn, FENODES, "");
        if (!fenodes.isEmpty()) {
            map.put(FENODES, fenodes);
        }

        // username / password
        String username = JdbcConfigReaders.getString(conn, "user", "");
        if (!username.isEmpty()) {
            map.put("username", username);
        }

        String password = JdbcConfigReaders.getString(conn, "password", "");
        if (!password.isEmpty()) {
            map.put("password", PasswordUtils.decodeIfEncrypted(password));
        }
    }

    // ======================== Source Option Mapping ========================

    /**
     * 将通用表单字段映射为 Doris Source 专属参数。
     *
     * <p>通用表单的 fetchSize / splitSize 是 JDBC 概念，Doris 不使用。
     * 这里将 fetchSize 自动映射为 doris.batch.size（语义最接近），
     * splitSize 在 Doris 中无对应概念，直接忽略。</p>
     */
    private void putDorisSourceOptions(Config config, Map<String, Object> map) {
        // 优先使用直接配置的 doris.batch.size
        Integer dorisBatchSize = JdbcConfigReaders.getInteger(config, "doris.batch.size", null);
        if (dorisBatchSize != null && dorisBatchSize > 0) {
            map.put("doris.batch.size", dorisBatchSize);
            return;
        }

        // 退而使用通用表单的 fetchSize
        Integer fetchSize = JdbcConfigReaders.getInteger(config, "fetchSize", null);
        if (fetchSize != null && fetchSize > 0) {
            map.put("doris.batch.size", fetchSize);
        }
    }

    // ======================== WHERE Extraction ========================

    /**
     * 从 SQL 中提取顶层 WHERE 子句内容作为过滤条件。
     *
     * <p>仅在括号深度为零、且不在字符串字面量内时匹配关键字，
     * 避免把子查询里的 WHERE 或含 WHERE 的字符串常量误判为顶层过滤；
     * 同时截掉 WHERE 之后的 ORDER BY / GROUP BY / HAVING / LIMIT 子句。</p>
     */
    private String extractWhereClause(String sql) {
        if (StringUtils.isBlank(sql)) {
            return "";
        }
        String trimmed = sql.trim();
        int whereIdx = findTopLevelKeyword(trimmed, "WHERE", 0);
        if (whereIdx < 0) {
            return "";
        }
        int start = whereIdx + "WHERE".length();
        int end = trimmed.length();
        for (String keyword : new String[] {"ORDER BY", "GROUP BY", "HAVING", "LIMIT"}) {
            int idx = findTopLevelKeyword(trimmed, keyword, start);
            if (idx >= 0 && idx < end) {
                end = idx;
            }
        }
        return trimmed.substring(start, end).trim();
    }

    /**
     * Locate a SQL keyword at parenthesis depth zero outside string literals,
     * starting at or after {@code from}.
     */
    private int findTopLevelKeyword(String sql, String keyword, int from) {
        String upper = sql.toUpperCase();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth > 0 || i < from || !upper.startsWith(keyword, i)) {
                continue;
            }
            boolean wordStart = i == 0 || !isSqlWordChar(upper.charAt(i - 1));
            boolean wordEnd = i + keyword.length() >= upper.length()
                    || !isSqlWordChar(upper.charAt(i + keyword.length()));
            if (wordStart && wordEnd) {
                return i;
            }
        }
        return -1;
    }

    private boolean isSqlWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '@' || c == '$';
    }

    /**
     * 构建单表表配置 (database / table / doris.filter.query)。
     */
    private void putTableConfig(Config config, Config conn, Map<String, Object> map) {
        String database = JdbcConfigReaders.getString(config, "database", "");
        if (database.isEmpty()) {
            database = JdbcConfigReaders.getString(conn, "database", "");
        }
        if (!database.isEmpty()) {
            map.put("database", database);
        }

        // The single-table workflow persists the destination name as
        // targetTableName. Doris has a custom sink builder instead of the
        // shared JDBC target resolver, so translate that workflow field to
        // SeaTunnel's required table option here.
        String table = JdbcConfigReaders.getString(config, "targetTableName", "");
        if (table.isEmpty()) {
            table = JdbcConfigReaders.getString(config, "table", "");
        }
        if (!table.isEmpty()) {
            map.put("table", table);
        }

        // doris.filter.query — Doris Source 插件的数据过滤参数
        String filterQuery = JdbcConfigReaders.getString(config, "doris.filter.query", "");
        if (!filterQuery.isEmpty()) {
            map.put("doris.filter.query", filterQuery);
        }
    }

    /**
     * 构建 Doris sink 特有配置。
     * 前端通用表单可能不传 sink.label-prefix / doris.config，后端自动填充默认值。
     */
    private void putDorisSinkConfig(Config config, Map<String, Object> map, HoconBuildContext context) {
        // sink.enable-2pc — 输出布尔值。开启 2pc 时 label-prefix 必须是任务级
        // 稳定值：故障恢复期需要按前缀追踪预提交事务，不接受每次构建重新生成。
        Boolean enable2pc = JdbcConfigReaders.getBoolean(config, "sink.enable-2pc", false);
        String labelPrefix = JdbcConfigReaders.getString(config, "sink.label-prefix", "");
        if (Boolean.TRUE.equals(enable2pc)) {
            map.put("sink.enable-2pc", true);
            if (StringUtils.isBlank(labelPrefix)) {
                throw new IllegalArgumentException(
                        "sink.label-prefix is required when sink.enable-2pc is enabled; "
                                + "use a stable task-level value");
            }
            map.put("sink.label-prefix", labelPrefix);
        } else {
            // 非 2pc 下 label 仅用于导入去重展示，前端未传则自动生成
            map.put("sink.label-prefix", StringUtils.isNotBlank(labelPrefix)
                    ? labelPrefix
                    : "seatunnel_" + System.currentTimeMillis());
        }

        Boolean enableDelete = JdbcConfigReaders.getBoolean(config, "sink.enable-delete", null);
        if (enableDelete != null) {
            map.put("sink.enable-delete", enableDelete);
        }

        // schema_save_mode — 默认 CREATE_SCHEMA_WHEN_NOT_EXIST
        String schemaSaveMode = JdbcConfigReaders.getString(config, "schema_save_mode", "");
        if (schemaSaveMode.isEmpty()) {
            schemaSaveMode = "CREATE_SCHEMA_WHEN_NOT_EXIST";
        }
        map.put("schema_save_mode", schemaSaveMode);

        String dataSaveMode = JdbcConfigReaders.getString(config, "data_save_mode", "");
        if (!dataSaveMode.isEmpty()) {
            map.put("data_save_mode", dataSaveMode);
        }

        // doris.batch.size — 通用表单的 batchSize/batch_size 映射到 Doris 专属参数
        Integer dorisBatchSize = JdbcConfigReaders.getInteger(config, "doris.batch.size", null);
        if (dorisBatchSize == null || dorisBatchSize <= 0) {
            dorisBatchSize = JdbcConfigReaders.getInteger(config, "batchSize", null);
        }
        if (dorisBatchSize == null || dorisBatchSize <= 0) {
            dorisBatchSize = JdbcConfigReaders.getInteger(config, "batch_size", null);
        }
        if (dorisBatchSize != null && dorisBatchSize > 0) {
            map.put("doris.batch.size", dorisBatchSize);
        }

        // doris.config — 必填，前端未传则使用 JSON 格式默认值
        Map<String, Object> dorisConfig = new HashMap<>();
        JdbcConfigReaders.appendConfigObject(config, "doris.config", dorisConfig);
        if (dorisConfig.isEmpty()) {
            dorisConfig.put("format", "json");
            dorisConfig.put("read_json_by_line", "true");
        }
        map.put("doris.config", dorisConfig);

        // save_mode_create_template — 条件守卫：仅在需要建表时注入模板
        // 模板优先级：用户显式填写 > 按主键选择的默认模板 > 不输出
        if ("CREATE_SCHEMA_WHEN_NOT_EXIST".equals(schemaSaveMode)
                || "RECREATE_SCHEMA".equals(schemaSaveMode)) {
            String template = JdbcConfigReaders.getString(config, "save_mode_create_template", "");
            if (template.isEmpty()) {
                Integer replicaCount = JdbcConfigReaders.getInteger(config, "dorisReplicaCount", 1);
                if (replicaCount == null || replicaCount <= 0) {
                    throw new IllegalArgumentException("dorisReplicaCount must be greater than zero");
                }
                template = hasConfiguredPrimaryKeys(config)
                        ? defaultCreateTableTemplate(replicaCount)
                        : duplicateKeyCreateTableTemplate(replicaCount);
            }
            if (template != null && !template.isEmpty()) {
                map.put("save_mode_create_template", template);
            }
        }
    }

    /**
     * 无主键表不能使用 UNIQUE KEY 模板：${rowtype_primary_key} 展开为空会
     * 生成非法 DDL。回退到引擎支持的 ${rowtype_duplicate_key} 占位符。
     */
    private String duplicateKeyCreateTableTemplate(int replicaCount) {
        return "CREATE TABLE IF NOT EXISTS `${database}`.`${table_name}` (\n"
                + "    ${rowtype_fields}\n"
                + ") ENGINE=OLAP\n"
                + "    DUPLICATE KEY (${rowtype_duplicate_key})\n"
                + "    COMMENT '${comment}'\n"
                + "    DISTRIBUTED BY HASH (${rowtype_duplicate_key})\n"
                + "    PROPERTIES (\n"
                + "        \"replication_allocation\" = \"tag.location.default: " + replicaCount + "\",\n"
                + "        \"in_memory\" = \"false\",\n"
                + "        \"storage_format\" = \"V2\",\n"
                + "        \"disable_auto_compaction\" = \"false\"\n"
                + "    )";
    }

    private boolean hasConfiguredPrimaryKeys(Config config) {
        String primaryKey = JdbcConfigReaders.getString(config, "primaryKey", "");
        if (StringUtils.isBlank(primaryKey)) {
            primaryKey = JdbcConfigReaders.getString(config, "primary_keys", "");
        }
        return StringUtils.isNotBlank(primaryKey);
    }

    @Override
    public String sourceTemplate() {
        return ""
                + "  Doris {\n"
                + "    datasourceId = @\n"
                + "    fenodes = \"127.0.0.1:8030\"\n"
                + "    database = \"demo\"\n"
                + "    table = \"user\"\n"
                + "  }\n";
    }

    @Override
    public String sinkTemplate() {
        return ""
                + "  Doris {\n"
                + "    datasourceId = @\n"
                + "    fenodes = \"127.0.0.1:8030\"\n"
                + "    database = \"demo\"\n"
                + "    table = \"user_sink\"\n"
                + "    sink.label-prefix = \"test\"\n"
                + "    sink.enable-2pc = true\n"
                + "    doris.config {\n"
                + "      format = \"json\"\n"
                + "      read_json_by_line = \"true\"\n"
                + "    }\n"
                + "  }\n";
    }

    /**
     * Doris OLAP 专属建表模板（有主键表，UNIQUE KEY 支持 upsert 语义）。
     *
     * <p>与 JDBC 关系型模板完全不同：
     * <ul>
     *   <li>ENGINE=OLAP — Doris 列存引擎</li>
     *   <li>UNIQUE KEY — 支持 upsert 语义</li>
     *   <li>DISTRIBUTED BY HASH — 数据分桶策略</li>
     *   <li>replication_allocation — 副本数</li>
     * </ul>
     */
    public String defaultCreateTableTemplate() {
        return defaultCreateTableTemplate(1);
    }

    private String defaultCreateTableTemplate(int replicaCount) {
        return "CREATE TABLE IF NOT EXISTS `${database}`.`${table_name}` (\n"
                + "    ${rowtype_primary_key},\n"
                + "    ${rowtype_fields}\n"
                + ") ENGINE=OLAP\n"
                + "    UNIQUE KEY (${rowtype_primary_key})\n"
                + "    COMMENT '${comment}'\n"
                + "    DISTRIBUTED BY HASH (${rowtype_primary_key})\n"
                + "    PROPERTIES (\n"
                + "        \"replication_allocation\" = \"tag.location.default: " + replicaCount + "\",\n"
                + "        \"in_memory\" = \"false\",\n"
                + "        \"storage_format\" = \"V2\",\n"
                + "        \"disable_auto_compaction\" = \"false\"\n"
                + "    )";
    }
}
