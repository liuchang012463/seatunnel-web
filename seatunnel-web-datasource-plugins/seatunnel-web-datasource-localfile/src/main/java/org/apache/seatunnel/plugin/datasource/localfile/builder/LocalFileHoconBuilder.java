package org.apache.seatunnel.plugin.datasource.localfile.builder;

import com.google.auto.service.AutoService;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds SeaTunnel 2.3.13 LocalFile source/sink HOCON for binary file transfer.
 *
 * <p>LocalFile uses {@code sync_mode}/{@code target_path}/{@code update_strategy}/
 * {@code compare_mode} for incremental update reads (unlike FtpFile's
 * {@code read_update_info}/{@code file_details_info}), and supports the same binary
 * read options as the remote file connectors.</p>
 */
@AutoService(DataSourceHoconBuilder.class)
public class LocalFileHoconBuilder implements DataSourceHoconBuilder {

    private static final Set<String> INTERNAL_KEYS = new HashSet<>(Arrays.asList(
            "dataSourceId", "datasourceId", "dbType", "connectorType", "pluginName", "nodeId", "nodeName",
            "sourceDatasourceId", "targetDatasourceId", "syncType", "scheduleType"));

    @Override
    public String pluginName() {
        return "LocalFile";
    }

    @Override
    public Config buildSourceHocon(HoconBuildContext context) {
        Map<String, Object> node = nodeValues(context);
        Map<String, Object> result = new LinkedHashMap<>();
        put(result, node, "path", "path");
        put(result, node, "fileFilterPattern", "file_filter_pattern");
        put(result, node, "filenameExtension", "filename_extension");
        put(result, node, "binaryChunkSize", "binary_chunk_size");
        put(result, node, "binaryCompleteFileMode", "binary_complete_file_mode");
        result.put("file_format_type", "binary");
        if ("INCREMENTAL".equalsIgnoreCase(string(node.get("syncType")))) {
            result.put("sync_mode", "update");
            put(result, node, "targetPath", "target_path");
            result.put("update_strategy", defaultString(node.get("updateStrategy"), "distcp"));
            result.put("compare_mode", defaultString(node.get("compareMode"), "len_mtime"));
        }
        appendExtras(result, node);
        require(result, "path");
        return ConfigFactory.parseMap(result);
    }

    @Override
    public Config buildSinkHocon(HoconBuildContext context) {
        Map<String, Object> node = nodeValues(context);
        Map<String, Object> result = new LinkedHashMap<>();
        put(result, node, "targetPath", "path");
        String target = string(result.get("path"));
        result.put("tmp_path", defaultString(node.get("tmpPath"), stripTrailingSlash(target) + "-seatunnel-tmp"));
        result.put("file_format_type", "binary");
        appendExtras(result, node);
        require(result, "path");
        return ConfigFactory.parseMap(result);
    }

    private Map<String, Object> nodeValues(HoconBuildContext context) {
        return context.getNodeConfig() == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(context.getNodeConfig().root().unwrapped());
    }

    private static void put(Map<String, Object> target, Map<String, Object> source, String from, String to) {
        Object value = source.get(from);
        if (value != null && !string(value).isEmpty()) {
            target.put(to, value);
        }
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

    private static void require(Map<String, Object> values, String key) {
        if (string(values.get(key)).isEmpty()) {
            throw new IllegalArgumentException("Missing required file option: " + key);
        }
    }

    private static String defaultString(Object value, String fallback) {
        String text = string(value);
        return text.isEmpty() ? fallback : text;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || "/".equals(value)) {
            return value;
        }
        return value.replaceAll("/+$", "");
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
