package org.apache.seatunnel.plugin.datasource.sftp.builder;

import com.google.auto.service.AutoService;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;

import java.util.HashMap;
import java.util.Map;

@AutoService(DataSourceHoconBuilder.class)
public class SftpBatchBuilder implements DataSourceHoconBuilder {

    @Override
    public String pluginName() {
        return "SFTP";
    }

    @Override
    public Config buildSourceHocon(HoconBuildContext context) {
        Config conn = context.getConnectionConfig();
        Config nodeConfig = context.getNodeConfig();

        Map<String, Object> map = new HashMap<>(16);

        putSftpConnConfig(conn, map);

        String filePath = resolveFilePath(nodeConfig);
        if (!filePath.isEmpty()) {
            map.put("path", filePath);
        }

        String format = resolveFormat(nodeConfig);
        map.put("file_format_type", format);

        if ("text".equals(format) || "csv".equals(format)) {
            String delimiter = firstNonBlank(
                    getString(nodeConfig, "delimiter", ""),
                    getString(nodeConfig, "field_delimiter", ""),
                    ",");
            map.put("field_delimiter", delimiter);

            Boolean hasHeader = getBoolean(nodeConfig, "hasHeader", null);
            if (hasHeader == null) {
                hasHeader = getBoolean(nodeConfig, "csv_use_header_line", false);
            }
            if (Boolean.TRUE.equals(hasHeader)) {
                map.put("csv_use_header_line", true);
            }
        }

        String compression = firstNonBlank(
                getString(nodeConfig, "compression", ""),
                getString(nodeConfig, "compression_codec", ""),
                "none");
        if (compression != null && !"none".equals(compression) && !compression.isEmpty()) {
            map.put("compression_codec", compression);
        }

        return ConfigFactory.parseMap(map);
    }

    @Override
    public Config buildSinkHocon(HoconBuildContext context) {
        Config conn = context.getConnectionConfig();
        Config nodeConfig = context.getNodeConfig();

        Map<String, Object> map = new HashMap<>(16);

        putSftpConnConfig(conn, map);

        String filePath = resolveFilePath(nodeConfig);
        if (!filePath.isEmpty()) {
            map.put("path", filePath);
        }

        String format = resolveFormat(nodeConfig);
        map.put("file_format_type", format);

        if ("text".equals(format) || "csv".equals(format)) {
            String delimiter = firstNonBlank(
                    getString(nodeConfig, "delimiter", ""),
                    getString(nodeConfig, "field_delimiter", ""),
                    ",");
            map.put("field_delimiter", delimiter);
        }

        String fileNameExpression = firstNonBlank(
                getString(nodeConfig, "fileNameExpression", ""),
                getString(nodeConfig, "file_name_expression", ""),
                "${transactionId}_${now}");
        map.put("file_name_expression", fileNameExpression);

        String behavior = firstNonBlank(
                getString(nodeConfig, "behavior", ""),
                getString(nodeConfig, "behavior_when_file_exists", ""),
                "DEFAULT");
        map.put("behavior_when_file_exists", behavior);

        return ConfigFactory.parseMap(map);
    }

    @Override
    public boolean supportsSource() {
        return true;
    }

    @Override
    public boolean supportsSink() {
        return true;
    }

    private void putSftpConnConfig(Config conn, Map<String, Object> map) {
        String host = getString(conn, "host", "");
        String port = getString(conn, "port", "22");

        map.put("host", host);
        map.put("port", Integer.parseInt(port.isEmpty() ? "22" : port));

        String user = firstNonBlank(getString(conn, "user", ""), getString(conn, "username", ""));
        map.put("user", user);

        String password = getString(conn, "password", "");
        if (password != null && !password.isEmpty()) {
            map.put("password", password);
        }
    }

    private String resolveFilePath(Config nodeConfig) {
        return firstNonBlank(
                getString(nodeConfig, "filePath", ""),
                getString(nodeConfig, "path", ""),
                getString(nodeConfig, "table", ""),
                getString(nodeConfig, "table_path", ""),
                getString(nodeConfig, "targetTableName", ""));
    }

    private String resolveFormat(Config nodeConfig) {
        return firstNonBlank(
                getString(nodeConfig, "format", ""),
                getString(nodeConfig, "file_format_type", ""),
                "csv");
    }

    private String getString(Config config, String key, String defaultValue) {
        if (config != null && config.hasPath(key)) {
            Object value = config.getAnyRef(key);
            if (value == null) {
                return defaultValue;
            }
            return String.valueOf(value).trim();
        }
        return defaultValue;
    }

    private Boolean getBoolean(Config config, String key, Boolean defaultValue) {
        if (config != null && config.hasPath(key)) {
            return config.getBoolean(key);
        }
        return defaultValue;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    @Override
    public String sourceTemplate() {
        return ""
                + "  SftpFile {\n"
                + "    host = \"127.0.0.1\"\n"
                + "    port = 22\n"
                + "    user = \"user\"\n"
                + "    password = \"****\"\n"
                + "    path = \"/path/to/file.csv\"\n"
                + "    file_format_type = \"csv\"\n"
                + "    field_delimiter = \",\"\n"
                + "  }\n";
    }

    @Override
    public String sinkTemplate() {
        return ""
                + "  SftpFile {\n"
                + "    host = \"127.0.0.1\"\n"
                + "    port = 22\n"
                + "    user = \"user\"\n"
                + "    password = \"****\"\n"
                + "    path = \"/path/to/output\"\n"
                + "    file_format_type = \"csv\"\n"
                + "    file_name_expression = \"\\${transactionId}_\\${now}\"\n"
                + "  }\n";
    }
}
