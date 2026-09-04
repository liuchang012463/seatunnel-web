package org.apache.seatunnel.plugin.datasource.ftp.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public abstract class AbstractRemoteFileHoconBuilder implements DataSourceHoconBuilder {
    private static final Set<String> INTERNAL_KEYS = new HashSet<>(Arrays.asList(
            "dataSourceId", "datasourceId", "dbType", "connectorType", "pluginName", "nodeId", "nodeName",
            "sourceDatasourceId", "targetDatasourceId", "syncType", "scheduleType"));

    @Override
    public Config buildSourceHocon(HoconBuildContext context) {
        Map<String, Object> node = nodeValues(context);
        Map<String, Object> result = connectionValues(context);
        put(result, node, "path", "path");
        put(result, node, "fileFilterPattern", "file_filter_pattern");
        put(result, node, "filenameExtension", "filename_extension");
        put(result, node, "binaryChunkSize", "binary_chunk_size");
        put(result, node, "binaryCompleteFileMode", "binary_complete_file_mode");
        result.put("file_format_type", "binary");
        if ("INCREMENTAL".equalsIgnoreCase(string(node.get("syncType")))) {
            result.put("read_update_info", true);
            put(result, node, "targetPath", "target_path");
            result.put("update_strategy", defaultString(node.get("updateStrategy"), "only_add"));
            result.put("file_details_info", defaultString(node.get("compareMode"), "len_mtime"));
        }
        appendExtras(result, node);
        require(result, "path");
        return ConfigFactory.parseMap(result);
    }

    @Override
    public Config buildSinkHocon(HoconBuildContext context) {
        Map<String, Object> node = nodeValues(context);
        Map<String, Object> result = connectionValues(context);
        put(result, node, "targetPath", "path");
        String target = string(result.get("path"));
        result.put("tmp_path", defaultString(node.get("tmpPath"), stripTrailingSlash(target) + "-seatunnel-tmp"));
        result.put("file_format_type", "binary");
        result.put("is_enable_transaction", true);
        appendExtras(result, node);
        require(result, "path");
        return ConfigFactory.parseMap(result);
    }

    protected abstract void appendProtocolConnection(Map<String, Object> result, Map<String, Object> connection);

    private Map<String, Object> connectionValues(HoconBuildContext context) {
        Map<String, Object> connection = context.getConnectionConfig() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(context.getConnectionConfig().root().unwrapped());
        Map<String, Object> result = new LinkedHashMap<>();
        put(result, connection, "host", "host");
        put(result, connection, "port", "port");
        put(result, connection, "user", "user");
        put(result, connection, "password", "password");
        appendProtocolConnection(result, connection);
        return result;
    }

    private Map<String, Object> nodeValues(HoconBuildContext context) {
        return context.getNodeConfig() == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(context.getNodeConfig().root().unwrapped());
    }

    @SuppressWarnings("unchecked")
    private void appendExtras(Map<String, Object> result, Map<String, Object> node) {
        Object extras = node.get("extraParams");
        if (extras instanceof Map) {
            ((Map<String, Object>) extras).forEach((key, value) -> {
                if (!INTERNAL_KEYS.contains(key) && value != null && !result.containsKey(key)) {
                    result.put(key, value);
                }
            });
        }
    }

    protected static void put(Map<String, Object> target, Map<String, Object> source, String from, String to) {
        Object value = source.get(from);
        if (value != null && !string(value).isEmpty()) { target.put(to, value); }
    }

    protected static String enumValue(Object value, String fallback) {
        return defaultString(value, fallback).toLowerCase(Locale.ROOT);
    }

    private static void require(Map<String, Object> values, String key) {
        if (string(values.get(key)).isEmpty()) { throw new IllegalArgumentException("Missing required file option: " + key); }
    }

    private static String defaultString(Object value, String fallback) {
        String text = string(value);
        return text.isEmpty() ? fallback : text;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || "/".equals(value)) { return value; }
        return value.replaceAll("/+$", "");
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
