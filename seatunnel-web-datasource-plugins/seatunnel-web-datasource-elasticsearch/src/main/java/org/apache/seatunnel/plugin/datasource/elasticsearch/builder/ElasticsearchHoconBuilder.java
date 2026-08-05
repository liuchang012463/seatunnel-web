package org.apache.seatunnel.plugin.datasource.elasticsearch.builder;

import com.google.auto.service.AutoService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.elasticsearch.param.ElasticsearchAuthType;
import org.apache.seatunnel.plugin.datasource.elasticsearch.param.ElasticsearchConnectionParam;
import org.apache.seatunnel.plugin.datasource.elasticsearch.param.ElasticsearchConnectionParamConverter;
import org.apache.seatunnel.web.common.utils.JSONUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@AutoService(DataSourceHoconBuilder.class)
public class ElasticsearchHoconBuilder implements DataSourceHoconBuilder {

    private static final Set<String> INTERNAL_KEYS = Set.of(
            "id", "dataSourceId", "datasourceId", "dbType", "connectorType", "pluginName",
            "connectorName", "nodeId", "nodeName", "nodeType", "mode", "jobMode", "wholeSync",
            "sourceId", "sinkId", "extraParams", "config", "meta", "title", "description",
            "readMode", "targetMode", "autoCreateTable", "writeMode", "incrementalConfig",
            "sql", "table", "table_path", "table_list", "tableList", "targetTableName",
            "fetchSize", "splitSize", "startMode", "stopMode", "schemaChange", "batchSize",
            "exactlyOnce", "enableUpsert", "fieldIde", "fieldId", "primaryKey");

    private static final Map<String, String> KEY_MAPPING = Map.ofEntries(
            Map.entry("schemaSaveMode", "schema_save_mode"),
            Map.entry("dataSaveMode", "data_save_mode"),
            Map.entry("indexType", "index_type"),
            Map.entry("primaryKeys", "primary_keys"),
            Map.entry("keyDelimiter", "key_delimiter"),
            Map.entry("authType", "auth_type"),
            Map.entry("apiKeyId", "auth.api_key_id"),
            Map.entry("apiKey", "auth.api_key"),
            Map.entry("apiKeyEncoded", "auth.api_key_encoded"),
            Map.entry("maxRetryCount", "max_retry_count"),
            Map.entry("maxBatchSize", "max_batch_size"),
            Map.entry("tlsVerifyCertificate", "tls_verify_certificate"),
            Map.entry("tlsVerifyHostname", "tls_verify_hostname"),
            Map.entry("tlsKeystorePath", "tls_keystore_path"),
            Map.entry("tlsKeystorePassword", "tls_keystore_password"),
            Map.entry("tlsTruststorePath", "tls_truststore_path"),
            Map.entry("tlsTruststorePassword", "tls_truststore_password"),
            Map.entry("searchType", "search_type"),
            Map.entry("searchApiType", "search_api_type"),
            Map.entry("sqlQuery", "sql_query"),
            Map.entry("scrollTime", "scroll_time"),
            Map.entry("scrollSize", "scroll_size"),
            Map.entry("arrayColumn", "array_column"),
            Map.entry("pitKeepAlive", "pit_keep_alive"),
            Map.entry("pitBatchSize", "pit_batch_size"),
            Map.entry("runtimeFields", "runtime_fields"),
            Map.entry("vectorizationFields", "vectorization_fields"),
            Map.entry("vectorDimensions", "vector_dimensions"));

    private static final Set<String> SOURCE_RESERVED = new HashSet<>(Arrays.asList(
            "hosts", "auth_type", "username", "password", "auth.api_key_id", "auth.api_key",
            "auth.api_key_encoded", "index", "tls_verify_certificate", "tls_verify_hostname",
            "tls_keystore_path", "tls_keystore_password", "tls_truststore_path",
            "tls_truststore_password"));

    private static final Set<String> SINK_RESERVED = new HashSet<>(Arrays.asList(
            "hosts", "auth_type", "username", "password", "auth.api_key_id", "auth.api_key",
            "auth.api_key_encoded", "index", "tls_verify_certificate", "tls_verify_hostname",
            "tls_keystore_path", "tls_keystore_password", "tls_truststore_path",
            "tls_truststore_password"));

    private static final Pattern CAMEL_CASE = Pattern.compile("([a-z0-9])([A-Z])");

    @Override
    public String pluginName() {
        return "ELASTICSEARCH";
    }

    @Override
    public Config buildSourceHocon(HoconBuildContext context) {
        ElasticsearchConnectionParam param = connectionParam(context);
        Map<String, Object> node = nodeValues(context);
        Map<String, Object> result = baseConfig(param);

        appendExtraParams(result, node, SOURCE_RESERVED);
        putFirstText(result, node, "index", "index", "table", "table_path");
        putIfPresent(result, "source", node.get("source"));
        putIfPresent(result, "query", node.get("query"));
        putIfPresent(result, "search_type", node.get("searchType"));
        putIfPresent(result, "search_api_type", node.get("searchApiType"));
        putIfPresent(result, "sql_query", node.get("sqlQuery"));
        putIfPresent(result, "scroll_time", node.get("scrollTime"));
        putIfPresent(result, "scroll_size", node.get("scrollSize"));
        putIfPresent(result, "array_column", node.get("arrayColumn"));
        putIfPresent(result, "pit_keep_alive", node.get("pitKeepAlive"));
        putIfPresent(result, "pit_batch_size", node.get("pitBatchSize"));
        putIfPresent(result, "runtime_fields", node.get("runtimeFields"));
        putIfPresent(result, "index_list", firstValue(node, "index_list", "indexList"));

        if ("sql".equalsIgnoreCase(stringValue(node.get("readMode")))) {
            result.put("search_type", "SQL");
            Object sql = node.get("sql");
            if (hasText(sql)) {
                result.put("sql_query", sql);
            }
            result.remove("query");
        }

        if (!hasText(result.get("index")) && !hasCollectionValue(result.get("index_list"))) {
            List<String> tableList = stringList(node.get("table_list"));
            if (!tableList.isEmpty()) {
                result.put("index_list", tableList.stream()
                        .map(index -> Collections.<String, Object>singletonMap("index", index))
                        .collect(java.util.stream.Collectors.toList()));
            }
        }

        result.putIfAbsent("query", defaultQuery());
        result.putIfAbsent("search_type", "DSL");
        result.putIfAbsent("search_api_type", "SCROLL");
        result.putIfAbsent("scroll_time", "1m");
        result.putIfAbsent("scroll_size", 100);
        validateSource(result);
        return toConfig(result);
    }

    @Override
    public Config buildSinkHocon(HoconBuildContext context) {
        ElasticsearchConnectionParam param = connectionParam(context);
        Map<String, Object> node = nodeValues(context);
        Map<String, Object> result = baseConfig(param);

        appendExtraParams(result, node, SINK_RESERVED);
        putFirstText(result, node, "index", "index", "targetIndex", "targetTableName", "table", "table_path");
        putIfPresent(result, "index_type", firstValue(node, "indexType"));
        putIfPresent(result, "primary_keys", firstValue(node, "primaryKeys"));
        putIfPresent(result, "key_delimiter", firstValue(node, "keyDelimiter"));
        putIfPresent(result, "schema_save_mode", firstValue(node, "schemaSaveMode"));
        putIfPresent(result, "data_save_mode", firstValue(node, "dataSaveMode"));
        putIfPresent(result, "vectorization_fields", firstValue(node, "vectorizationFields"));
        putIfPresent(result, "vector_dimensions", firstValue(node, "vectorDimensions"));

        if ("overwrite".equalsIgnoreCase(stringValue(node.get("writeMode")))) {
            result.put("data_save_mode", "DROP_DATA");
        } else if (!hasText(result.get("data_save_mode"))) {
            result.put("data_save_mode", "APPEND_DATA");
        }
        if ("upsert".equalsIgnoreCase(stringValue(node.get("writeMode")))
                && !hasValue(result.get("primary_keys"))) {
            String primaryKey = stringValue(node.get("primaryKey"));
            if (StringUtils.isNotBlank(primaryKey)) {
                result.put("primary_keys", splitList(primaryKey));
            }
        }

        result.putIfAbsent("schema_save_mode", "CREATE_SCHEMA_WHEN_NOT_EXIST");
        validateSink(result);
        return toConfig(result);
    }

    private ElasticsearchConnectionParam connectionParam(HoconBuildContext context) {
        ElasticsearchConnectionParam param = new ElasticsearchConnectionParamConverter()
                .createConnectionParams(context.getConnectionParam());
        new ElasticsearchConnectionParamConverter().checkDatasourceParam(param);
        return param;
    }

    private Map<String, Object> baseConfig(ElasticsearchConnectionParam param) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hosts", param.hostList());
        ElasticsearchAuthType authType = param.getAuthType() == null
                ? ElasticsearchAuthType.NONE : param.getAuthType();
        if (authType != ElasticsearchAuthType.NONE) {
            result.put("auth_type", authType.name().toLowerCase(Locale.ROOT));
        }
        if (authType == ElasticsearchAuthType.BASIC) {
            result.put("username", param.getUsername());
            result.put("password", param.getPassword());
        } else if (authType == ElasticsearchAuthType.API_KEY) {
            result.put("auth.api_key_id", param.getApiKeyId());
            result.put("auth.api_key", param.getApiKey());
        } else if (authType == ElasticsearchAuthType.API_KEY_ENCODED) {
            result.put("auth.api_key_encoded", param.getApiKeyEncoded());
        }
        result.put("tls_verify_certificate", param.getTlsVerifyCertificate());
        result.put("tls_verify_hostname", param.getTlsVerifyHostname());
        putIfText(result, "tls_keystore_path", param.getTlsKeystorePath());
        putIfText(result, "tls_keystore_password", param.getTlsKeystorePassword());
        putIfText(result, "tls_truststore_path", param.getTlsTruststorePath());
        putIfText(result, "tls_truststore_password", param.getTlsTruststorePassword());
        return result;
    }

    private void appendExtraParams(
            Map<String, Object> target,
            Map<String, Object> node,
            Set<String> reserved) {
        asMap(node.get("extraParams")).forEach((key, value) -> {
            if (INTERNAL_KEYS.contains(key)) {
                return;
            }
            String hoconKey = KEY_MAPPING.getOrDefault(key, toSnakeCase(key));
            if (!reserved.contains(hoconKey)) {
                target.put(hoconKey, convertExtraValue(hoconKey, value));
            }
        });
    }

    private Object convertExtraValue(String key, Object raw) {
        if (!(raw instanceof String)) {
            return raw;
        }
        String text = ((String) raw).trim();
        if (text.isEmpty()) {
            return text;
        }
        if (Set.of("tls_verify_certificate", "tls_verify_hostname").contains(key)) {
            return Boolean.parseBoolean(text);
        }
        if (Set.of("scroll_size", "pit_batch_size", "max_retry_count", "max_batch_size",
                "vector_dimensions").contains(key)) {
            return Integer.valueOf(text);
        }
        if (Set.of("pit_keep_alive").contains(key)) {
            return Long.valueOf(text);
        }
        if (Set.of("source", "primary_keys", "vectorization_fields").contains(key)) {
            return parseStringList(text);
        }
        if (Set.of("query", "array_column").contains(key)) {
            return parseObject(text);
        }
        if (Set.of("runtime_fields", "index_list").contains(key)) {
            return JSONUtils.parseObject(text, new TypeReference<List<Map<String, Object>>>() {});
        }
        return text;
    }

    private Map<String, Object> defaultQuery() {
        Map<String, Object> matchAll = new LinkedHashMap<>();
        matchAll.put("match_all", Collections.emptyMap());
        return matchAll;
    }

    private Object parseObject(String text) {
        try {
            return JSONUtils.parseObject(text, new TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Elasticsearch JSON parameter is invalid", e);
        }
    }

    private List<String> parseStringList(String text) {
        if (text.startsWith("[") && text.endsWith("]")) {
            return JSONUtils.toList(text, String.class);
        }
        return splitList(text);
    }

    private List<String> splitList(String text) {
        return Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(java.util.stream.Collectors.toList());
    }

    private Map<String, Object> nodeValues(HoconBuildContext context) {
        if (context == null || context.getNodeConfig() == null) {
            return Collections.emptyMap();
        }
        return new LinkedHashMap<>(context.getNodeConfig().root().unwrapped());
    }

    private void putFirstText(Map<String, Object> target, Map<String, Object> node,
                              String targetKey, String... candidates) {
        for (String candidate : candidates) {
            Object value = node.get(candidate);
            if (hasText(value)) {
                target.put(targetKey, value);
                return;
            }
        }
    }

    private Object firstValue(Map<String, Object> node, String... keys) {
        for (String key : keys) {
            Object value = node.get(key);
            if (hasValue(value)) {
                return value;
            }
        }
        return null;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (hasValue(value)) {
            target.put(key, value);
        }
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            target.put(key, value);
        }
    }

    private boolean hasValue(Object value) {
        return value != null && (!(value instanceof String) || StringUtils.isNotBlank((String) value));
    }

    private boolean hasCollectionValue(Object value) {
        if (value instanceof List) {
            return !((List<?>) value).isEmpty();
        }
        return hasValue(value);
    }

    private boolean hasText(Object value) {
        return value != null && StringUtils.isNotBlank(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) value);
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (value instanceof List) {
            return ((List<Object>) value).stream()
                    .filter(item -> item != null && StringUtils.isNotBlank(String.valueOf(item)))
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.toList());
        }
        if (value instanceof String && StringUtils.isNotBlank((String) value)) {
            return splitList((String) value);
        }
        return Collections.emptyList();
    }

    private String toSnakeCase(String key) {
        return CAMEL_CASE.matcher(key).replaceAll("$1_$2").toLowerCase(Locale.ROOT);
    }

    private void validateSource(Map<String, Object> config) {
        if (!hasValue(config.get("hosts"))) {
            throw new IllegalArgumentException("Elasticsearch Source hosts cannot be empty");
        }
        if (!hasText(config.get("index")) && !hasCollectionValue(config.get("index_list"))) {
            throw new IllegalArgumentException("Elasticsearch Source requires index or index_list");
        }
        String searchType = stringValue(config.get("search_type")).toUpperCase(Locale.ROOT);
        if (!Set.of("DSL", "SQL").contains(searchType)) {
            throw new IllegalArgumentException("Elasticsearch Source search_type must be DSL or SQL");
        }
        config.put("search_type", searchType);
        if ("SQL".equals(searchType) && !hasText(config.get("sql_query"))) {
            throw new IllegalArgumentException("Elasticsearch Source sql_query is required for SQL search");
        }
        String apiType = stringValue(config.get("search_api_type")).toUpperCase(Locale.ROOT);
        if (!Set.of("SCROLL", "PIT").contains(apiType)) {
            throw new IllegalArgumentException("Elasticsearch Source search_api_type must be SCROLL or PIT");
        }
        config.put("search_api_type", apiType);
        validatePositive(config, "scroll_size");
        validatePositive(config, "pit_batch_size");
    }

    private void validateSink(Map<String, Object> config) {
        if (!hasText(config.get("index"))) {
            throw new IllegalArgumentException("Elasticsearch Sink index cannot be empty");
        }
        if (!Set.of("RECREATE_SCHEMA", "CREATE_SCHEMA_WHEN_NOT_EXIST",
                "ERROR_WHEN_SCHEMA_NOT_EXIST", "IGNORE").contains(
                stringValue(config.get("schema_save_mode")).toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Elasticsearch Sink schema_save_mode is invalid");
        }
        if (!Set.of("DROP_DATA", "APPEND_DATA", "ERROR_WHEN_DATA_EXISTS").contains(
                stringValue(config.get("data_save_mode")).toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Elasticsearch Sink data_save_mode is invalid");
        }
        config.put("schema_save_mode", stringValue(config.get("schema_save_mode")).toUpperCase(Locale.ROOT));
        config.put("data_save_mode", stringValue(config.get("data_save_mode")).toUpperCase(Locale.ROOT));
    }

    private void validatePositive(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof Number && ((Number) value).longValue() <= 0) {
            throw new IllegalArgumentException("Elasticsearch " + key + " must be greater than 0");
        }
    }

    private Config toConfig(Map<String, Object> values) {
        Map<String, Object> quoted = new LinkedHashMap<>();
        values.forEach((key, value) -> quoted.put(
                key.contains(".") && !key.startsWith("\"") ? "\"" + key + "\"" : key,
                value));
        return ConfigFactory.parseMap(quoted);
    }
}
