package org.apache.seatunnel.web.api.offlineimport;

import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates an entire SQL dump before the import opens a write path to the target database. */
public final class SqlImportPlan {

    private static final Pattern SCHEMA_SWITCH = Pattern.compile(
            "(?is)^\\s*SET\\s+(?:CURRENT\\s+)?SCHEMA\\s+(\"(?:\"\"|[^\"])*\"|'(?:''|[^'])*'|[\\w$]+)\\s*$");
    private static final Pattern SEARCH_PATH_SWITCH = Pattern.compile(
            "(?is)^\\s*SET\\s+(?:SESSION\\s+)?search_path\\s*(?:TO|=)\\s*(.*?)\\s*$");
    private static final Pattern SET_CONFIG_SEARCH_PATH = Pattern.compile(
            "(?is)^\\s*SELECT\\s+(?:pg_catalog\\s*\\.\\s*)?set_config\\s*\\(\\s*'search_path'\\s*,\\s*'((?:''|[^'])*)'\\s*,\\s*(true|false)\\s*\\)\\s*$");

    private static final Set<String> BLOCKED_FILE_FUNCTIONS = Set.of(
            "PG_READ_FILE", "PG_READ_BINARY_FILE", "PG_WRITE_FILE", "LO_IMPORT", "LO_EXPORT");
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "PG_CATALOG", "INFORMATION_SCHEMA", "SYS_CATALOG", "SYS", "SYSTEM");
    private static final Set<String> BLOCKED_LEADING_COMMANDS = Set.of(
            "CONNECT", "USE", "HOST", "SPOOL", "START", "EXIT", "QUIT", "PROMPT", "REM",
            "SHUTDOWN", "SET_ROLE", "RESET_ROLE");

    public record Plan(List<SqlScriptSplitter.Statement> statements, int ignoredMetadata) {
        public int executableCount() {
            return statements.size();
        }
    }

    private SqlImportPlan() {
    }

    public static Plan prepare(String script, DbType dbType, String rawSourceSchema, String rawTargetSchema) {
        String sourceSchema = clean(rawSourceSchema);
        String targetSchema = clean(rawTargetSchema);
        String effectiveTarget = targetSchema == null ? sourceSchema : targetSchema;
        List<SqlScriptSplitter.Statement> planned = new ArrayList<>();
        int ignored = 0;

        for (SqlScriptSplitter.Statement parsed : SqlScriptSplitter.parse(script)) {
            if (parsed.isDirective()) {
                validateDirective(parsed.directive());
                ignored++;
                continue;
            }

            String sql = parsed.sql().trim();
            List<String> words = SqlSchemaMapper.words(sql);
            if (words.isEmpty()) {
                continue;
            }
            if (effectiveTarget != null && SqlSchemaMapper.hasDynamicSql(sql)) {
                throw new IllegalArgumentException("SQL 文件包含无法安全映射 Schema 的动态存储程序");
            }
            validateCommand(sql, words, parsed.isCopy(), dbType);
            if (isIgnorableMetadata(words)) {
                ignored++;
                continue;
            }

            String switched = rewriteSchemaSwitch(sql, words, sourceSchema, effectiveTarget);
            Set<String> writeSchemas = SqlSchemaMapper.writeSchemas(switched);
            validateWriteSchemas(writeSchemas, sourceSchema, effectiveTarget);

            String rewritten = SqlSchemaMapper.rewrite(switched, sourceSchema, effectiveTarget);
            if (effectiveTarget != null && startsWith(words, "CREATE", "SCHEMA")) {
                // The selected target schema is verified/created by the service before SQL runs.
                ignored++;
                continue;
            }
            if (parsed.isCopy() && dbType != DbType.KINGBASE) {
                throw new IllegalArgumentException("COPY FROM STDIN 仅支持 Kingbase SQL 导入");
            }
            planned.add(parsed.isCopy()
                    ? SqlScriptSplitter.Statement.copy(rewritten, parsed.copyData())
                    : SqlScriptSplitter.Statement.sql(rewritten));
        }

        if (planned.isEmpty()) {
            throw new IllegalArgumentException("SQL 文件没有可执行的导入语句");
        }
        return new Plan(List.copyOf(planned), ignored);
    }

    private static void validateDirective(String directive) {
        String[] parts = directive.trim().split("\\s+");
        if (parts.length == 2 && "\\restrict".equalsIgnoreCase(parts[0])) {
            return;
        }
        if ((parts.length == 1 || parts.length == 2) && "\\unrestrict".equalsIgnoreCase(parts[0])) {
            return;
        }
        throw new IllegalArgumentException("SQL 文件包含不支持的客户端指令: " + safeCommand(directive));
    }

    private static void validateCommand(String sql, List<String> words, boolean copy, DbType dbType) {
        String first = words.get(0);
        if (BLOCKED_LEADING_COMMANDS.contains(first)) {
            throw new IllegalArgumentException("SQL 文件包含不支持的客户端或重连命令: " + first);
        }
        if ("COPY".equals(first) && (!copy || dbType != DbType.KINGBASE)) {
            throw new IllegalArgumentException("仅允许 Kingbase 的 COPY ... FROM STDIN 数据段");
        }
        if (startsWith(words, "CREATE", "DATABASE") || startsWith(words, "ALTER", "DATABASE")
                || startsWith(words, "DROP", "DATABASE") || startsWith(words, "CREATE", "ROLE")
                || startsWith(words, "CREATE", "USER") || startsWith(words, "DROP", "ROLE")
                || startsWith(words, "DROP", "USER") || startsWith(words, "CREATE", "TABLESPACE")
                || startsWith(words, "DROP", "TABLESPACE")) {
            throw new IllegalArgumentException("SQL 文件包含不能在选定数据库中执行的管理命令: " + first + " " + words.get(1));
        }
        for (String word : words) {
            if (BLOCKED_FILE_FUNCTIONS.contains(word)) {
                throw new IllegalArgumentException("SQL 文件包含数据库服务器文件操作函数: " + word);
            }
        }
        if (sql.matches("(?is).*\\bSET\\s+(?:SESSION\\s+)?AUTHORIZATION\\b.*")
                || sql.matches("(?is).*\\bSET\\s+ROLE\\b.*")) {
            throw new IllegalArgumentException("SQL 文件不能切换数据库执行身份");
        }
        if (sql.matches("(?is).*\\bset_config\\s*\\(\\s*'search_path'.*")) {
            Matcher matcher = SET_CONFIG_SEARCH_PATH.matcher(sql);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("无法安全识别 SQL 文件中的 search_path 动态设置");
            }
        }
    }

    private static boolean isIgnorableMetadata(List<String> words) {
        String first = words.get(0);
        if ("GRANT".equals(first) || "REVOKE".equals(first)) {
            return true;
        }
        if (startsWith(words, "ALTER", "DEFAULT", "PRIVILEGES")) {
            return true;
        }
        return "ALTER".equals(first) && words.contains("OWNER") && words.contains("TO");
    }

    private static String rewriteSchemaSwitch(
            String sql, List<String> words, String sourceSchema, String targetSchema) {
        if (targetSchema == null) {
            return sql;
        }
        Matcher schema = SCHEMA_SWITCH.matcher(sql);
        if (schema.matches()) {
            String current = identifierValue(schema.group(1));
            validateSchemaSwitch(current, sourceSchema, targetSchema);
            return "SET SCHEMA " + SqlSchemaMapper.quoteIdentifier(targetSchema);
        }

        Matcher path = SEARCH_PATH_SWITCH.matcher(sql);
        if (path.matches()) {
            return "SET search_path TO " + mapSearchPath(path.group(1), sourceSchema, targetSchema);
        }

        Matcher config = SET_CONFIG_SEARCH_PATH.matcher(sql);
        if (config.matches()) {
            String mappedPath = mapSearchPath(config.group(1), sourceSchema, targetSchema);
            return "SELECT pg_catalog.set_config('search_path', '" + mappedPath.replace("'", "''")
                    + "', " + config.group(2).toLowerCase(Locale.ROOT) + ")";
        }
        return sql;
    }

    private static String mapSearchPath(String rawPath, String sourceSchema, String targetSchema) {
        String path = rawPath.trim();
        if (path.startsWith("'") && path.endsWith("'")) {
            path = identifierValue(path);
        } else if (path.startsWith("\"") && path.endsWith("\"")) {
            path = identifierValue(path);
        }
        if (path.isEmpty()) {
            return SqlSchemaMapper.quoteIdentifier(targetSchema);
        }
        String[] entries = path.split(",", -1);
        List<String> mapped = new ArrayList<>();
        for (String entry : entries) {
            String value = identifierValue(entry.trim());
            if (value.isEmpty()) {
                continue;
            }
            if (sourceSchema != null && value.equalsIgnoreCase(sourceSchema)) {
                mapped.add(SqlSchemaMapper.quoteIdentifier(targetSchema));
            } else if (value.equalsIgnoreCase(targetSchema)) {
                mapped.add(SqlSchemaMapper.quoteIdentifier(targetSchema));
            } else if ("PG_CATALOG".equalsIgnoreCase(value)) {
                mapped.add("pg_catalog");
            } else {
                throw new IllegalArgumentException("search_path 包含非源/目标 Schema: " + value
                        + "，请先填写正确的源 Schema");
            }
        }
        if (mapped.stream().noneMatch(value -> value.equals(SqlSchemaMapper.quoteIdentifier(targetSchema)))) {
            mapped.add(0, SqlSchemaMapper.quoteIdentifier(targetSchema));
        }
        return String.join(", ", mapped);
    }

    private static String identifierValue(String raw) {
        String value = raw.trim();
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        if (value.length() >= 2 && value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'') {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }

    private static void validateSchemaSwitch(String schema, String source, String target) {
        if (source == null && !schema.equalsIgnoreCase(target)) {
            throw new IllegalArgumentException("脚本显式切换到 Schema " + schema + "，请填写源 Schema 后再导入目标 Schema");
        }
        if (source != null && !schema.equalsIgnoreCase(source) && !schema.equalsIgnoreCase(target)) {
            throw new IllegalArgumentException("脚本切换到非源/目标 Schema " + schema);
        }
    }

    private static void validateWriteSchemas(Set<String> schemas, String source, String target) {
        for (String schema : schemas) {
            if (SYSTEM_SCHEMAS.contains(schema.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("SQL 文件尝试写入系统 Schema: " + schema);
            }
            if (target == null) {
                continue;
            }
            if (source == null && !schema.equalsIgnoreCase(target)) {
                throw new IllegalArgumentException("脚本显式写入 Schema " + schema
                        + "，请填写源 Schema 后再映射到目标 Schema");
            }
            if (source != null && !schema.equalsIgnoreCase(source) && !schema.equalsIgnoreCase(target)) {
                throw new IllegalArgumentException("脚本写入非源/目标 Schema " + schema);
            }
        }
    }

    private static boolean startsWith(List<String> words, String... expected) {
        if (words.size() < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (!expected[i].equals(words.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String safeCommand(String directive) {
        String trimmed = directive.trim();
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) {
            end++;
        }
        return end == 0 ? "未知" : trimmed.substring(0, end);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
