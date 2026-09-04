package org.apache.seatunnel.plugin.datasource.s3.builder;

import com.google.auto.service.AutoService;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.s3.client.ObjectStoragePathUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@AutoService(DataSourceHoconBuilder.class)
public class S3FileHoconBuilder implements DataSourceHoconBuilder {
    private static final String STATIC_PROVIDER =
            "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider";
    private static final String INSTANCE_PROFILE_PROVIDER =
            "com.amazonaws.auth.InstanceProfileCredentialsProvider";

    @Override
    public String pluginName() {
        return "S3File";
    }

    @Override
    public Config buildSourceHocon(HoconBuildContext context) {
        Map<String, Object> node = values(context.getNodeConfig());
        rejectIncremental(node);
        Map<String, Object> result = connectionValues(context);
        String path = resolveNodePath(context, node, "path");
        result.put("path", path);
        put(result, node, "fileFilterPattern", "file_filter_pattern");
        put(result, node, "filenameExtension", "filename_extension");
        put(result, node, "binaryChunkSize", "binary_chunk_size");
        put(result, node, "binaryCompleteFileMode", "binary_complete_file_mode");
        result.put("file_format_type", "binary");
        return toConfig(result);
    }

    @Override
    public Config buildSinkHocon(HoconBuildContext context) {
        Map<String, Object> node = values(context.getNodeConfig());
        rejectIncremental(node);
        Map<String, Object> result = connectionValues(context);
        String targetPath = resolveNodePath(context, node, "targetPath");
        result.put("path", targetPath);
        result.put("tmp_path", defaultString(node.get("tmpPath"), temporaryPath(targetPath)));
        result.put("file_format_type", "binary");
        result.put("is_enable_transaction", true);
        return toConfig(result);
    }

    private Map<String, Object> connectionValues(HoconBuildContext context) {
        Map<String, Object> connection = values(context.getConnectionConfig());
        String endpoint = require(connection, "endpoint");
        String bucket = require(connection, "bucket");
        String dbType = defaultString(connection.get("dbType"), "S3").toUpperCase(Locale.ROOT);
        String credentialMode = defaultString(connection.get("credentialMode"), "STATIC")
                .toUpperCase(Locale.ROOT);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bucket", "s3a://" + bucket);
        result.put("fs.s3a.endpoint", endpoint);
        if ("INSTANCE_PROFILE".equals(credentialMode)) {
            if (!"S3".equals(dbType)) {
                throw new IllegalArgumentException("MINIO supports static credentials only");
            }
            if (hasText(connection.get("accessKey")) || hasText(connection.get("secretKey"))) {
                throw new IllegalArgumentException(
                        "S3 INSTANCE_PROFILE mode must not include accessKey or secretKey");
            }
            result.put("fs.s3a.aws.credentials.provider", INSTANCE_PROFILE_PROVIDER);
        } else {
            result.put("fs.s3a.aws.credentials.provider", STATIC_PROVIDER);
            result.put("access_key", require(connection, "accessKey"));
            result.put("secret_key", require(connection, "secretKey"));
        }

        boolean pathStyle = "MINIO".equals(dbType)
                || Boolean.parseBoolean(String.valueOf(connection.get("pathStyleAccess")));
        Map<String, Object> hadoopProperties = new LinkedHashMap<>();
        hadoopProperties.put("\"fs.s3a.path.style.access\"", String.valueOf(pathStyle));
        hadoopProperties.put("\"fs.s3a.connection.ssl.enabled\"",
                String.valueOf(endpoint.toLowerCase(Locale.ROOT).startsWith("https://")));
        result.put("hadoop_s3_properties", hadoopProperties);
        return result;
    }

    private String resolveNodePath(
            HoconBuildContext context,
            Map<String, Object> node,
            String nodeKey) {
        Map<String, Object> connection = values(context.getConnectionConfig());
        String basePath = defaultString(connection.get("basePath"), "/");
        return ObjectStoragePathUtils.resolveWithinBase(basePath, require(node, nodeKey));
    }

    private void rejectIncremental(Map<String, Object> node) {
        if ("INCREMENTAL".equalsIgnoreCase(String.valueOf(node.get("syncType")))) {
            throw new IllegalArgumentException(
                    "SeaTunnel 2.3.13 S3File does not support incremental update sync");
        }
    }

    private Config toConfig(Map<String, Object> values) {
        Map<String, Object> hocon = new LinkedHashMap<>();
        values.forEach((key, value) -> hocon.put(
                key.contains(".") ? "\"" + key + "\"" : key,
                value));
        return ConfigFactory.parseMap(hocon);
    }

    private static Map<String, Object> values(Config config) {
        return config == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(config.root().unwrapped());
    }

    private static void put(
            Map<String, Object> target,
            Map<String, Object> source,
            String from,
            String to) {
        Object value = source.get(from);
        if (value != null && (!(value instanceof String) || StringUtils.isNotBlank((String) value))) {
            target.put(to, value);
        }
    }

    private static String require(Map<String, Object> values, String key) {
        String value = values.get(key) == null ? "" : String.valueOf(values.get(key)).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing required S3File option: " + key);
        }
        return value;
    }

    private static String defaultString(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static boolean hasText(Object value) {
        return value != null && StringUtils.isNotBlank(String.valueOf(value));
    }

    private static String temporaryPath(String targetPath) {
        return "/".equals(targetPath)
                ? "/seatunnel-tmp"
                : targetPath.replaceAll("/+$", "") + "-seatunnel-tmp";
    }
}
