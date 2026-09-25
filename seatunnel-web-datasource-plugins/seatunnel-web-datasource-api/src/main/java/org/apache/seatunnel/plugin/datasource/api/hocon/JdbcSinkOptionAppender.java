package org.apache.seatunnel.plugin.datasource.api.hocon;

import com.typesafe.config.Config;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConfigReaders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.apache.seatunnel.plugin.datasource.api.hocon.JdbcBatchConstants.*;

public class JdbcSinkOptionAppender {

    public void append(Config config, Map<String, Object> map) {
        appendSaveMode(config, map);
        appendUpsertOptions(config, map);
        appendAdvancedOptions(config, map);
    }

    private void appendSaveMode(Config config, Map<String, Object> map) {
        boolean autoCreateTable = JdbcConfigReaders.getBoolean(config, AUTO_CREATE_TABLE, false);

        String writeMode = JdbcConfigReaders.getString(config, "writeMode", "append");
        String dataSaveMode;

        switch (writeMode.toLowerCase()) {
            case "overwrite":
                dataSaveMode = "DROP_DATA";
                break;
            case "append":
            case "upsert":
            default:
                dataSaveMode = "APPEND_DATA";
                break;
        }

        String configuredDataSaveMode = JdbcConfigReaders.getString(config, "dataSaveMode", "");
        if (StringUtils.isBlank(configuredDataSaveMode)) {
            configuredDataSaveMode = JdbcConfigReaders.getString(config, "data_save_mode", "");
        }

        map.put("data_save_mode",
                StringUtils.isNotBlank(configuredDataSaveMode)
                        ? configuredDataSaveMode
                        : dataSaveMode);

        String schemaSaveMode = JdbcConfigReaders.getString(config, "schemaSaveMode", "");
        if (StringUtils.isBlank(schemaSaveMode)) {
            schemaSaveMode = JdbcConfigReaders.getString(config, "schema_save_mode", "");
        }

        map.put("schema_save_mode",
                StringUtils.isNotBlank(schemaSaveMode)
                        ? schemaSaveMode
                        : (autoCreateTable
                        ? "CREATE_SCHEMA_WHEN_NOT_EXIST"
                        : "ERROR_WHEN_SCHEMA_NOT_EXIST"));
    }

    private void appendUpsertOptions(Config config, Map<String, Object> map) {
        String writeMode = JdbcConfigReaders.getString(config, "writeMode", "append");

        Boolean configuredEnableUpsert = readBooleanObject(config, "enableUpsert");
        if (configuredEnableUpsert == null) {
            configuredEnableUpsert = readBooleanObject(config, "enable_upsert");
        }

        List<String> primaryKeys = resolvePrimaryKeys(config);
        boolean writeModeIsUpsert = "upsert".equalsIgnoreCase(writeMode);

        if (writeModeIsUpsert && primaryKeys.isEmpty()) {
            throw new IllegalArgumentException("Primary key is required when writeMode is upsert");
        }

        // The engine defaults enable_upsert to true; only emit it when the user
        // configured it explicitly or upsert semantics must be forced.
        if (configuredEnableUpsert != null) {
            map.put(ENABLE_UPSERT, configuredEnableUpsert);
        } else if (writeModeIsUpsert) {
            map.put(ENABLE_UPSERT, true);
        }

        if (!primaryKeys.isEmpty()) {
            map.put("primary_keys", primaryKeys);
        }
    }

    private void appendAdvancedOptions(Config config, Map<String, Object> map) {
        Integer batchSize = JdbcConfigReaders.getInteger(config, BATCH_SIZE, 1000);
        if (batchSize != null && batchSize > 0) {
            map.put("batch_size", batchSize);
        }

        String fieldIde = JdbcConfigReaders.getString(config, "fieldIde", "");
        if (StringUtils.isBlank(fieldIde)) {
            fieldIde = JdbcConfigReaders.getString(config, "field_ide", "");
        }

        if (StringUtils.isNotBlank(fieldIde)) {
            map.put("field_ide", fieldIde);
        }

        if (config.hasPath("exactlyOnce")) {
            appendExactlyOnce(config, map, JdbcConfigReaders.getBoolean(config, "exactlyOnce", false));
        }

        if (config.hasPath("is_exactly_once")) {
            appendExactlyOnce(config, map, JdbcConfigReaders.getBoolean(config, "is_exactly_once", false));
        } else if ("true".equalsIgnoreCase(extraParam(config, "is_exactly_once"))) {
            appendExactlyOnce(config, map, true);
        }
    }

    /**
     * The engine rejects exactly-once jobs without an XA data source class, so
     * fail at build time with an actionable message instead.
     */
    private void appendExactlyOnce(Config config, Map<String, Object> map, boolean exactlyOnce) {
        map.put(IS_EXACTLY_ONCE, exactlyOnce);
        if (exactlyOnce && StringUtils.isBlank(extraParam(config, "xa_data_source_class_name"))
                && StringUtils.isBlank(JdbcConfigReaders.getString(config, "xa_data_source_class_name", ""))) {
            throw new IllegalArgumentException(
                    "xa_data_source_class_name is required when is_exactly_once is enabled (e.g. com.mysql.cj.jdbc.MysqlXADataSource)");
        }
    }

    private String extraParam(Config config, String key) {
        if (config == null || !config.hasPath(EXTRA_PARAMS)) {
            return "";
        }
        try {
            for (Config item : config.getConfigList(EXTRA_PARAMS)) {
                if (key.equalsIgnoreCase(JdbcConfigReaders.getString(item, KEY, ""))) {
                    Object value = item.hasPath(VALUE) ? item.getValue(VALUE).unwrapped() : "";
                    return value == null ? "" : String.valueOf(value).trim();
                }
            }
        } catch (Exception ignored) {
            // A malformed extraParams list is handled by the generic appender.
        }
        return "";
    }

    private List<String> resolvePrimaryKeys(Config config) {
        List<String> primaryKeys = new ArrayList<>();

        try {
            if (config != null && config.hasPath("primary_keys")) {
                List<String> values = config.getStringList("primary_keys");
                for (String value : values) {
                    addDistinct(primaryKeys, value);
                }
            }
        } catch (Exception ignored) {
            // Continue to read primaryKey.
        }

        if (CollectionUtils.isNotEmpty(primaryKeys)) {
            return primaryKeys;
        }

        String primaryKey = JdbcConfigReaders.getString(config, PRIMARY_KEY, "");
        if (StringUtils.isBlank(primaryKey)) {
            primaryKey = JdbcConfigReaders.getString(config, "primary_keys", "");
        }

        if (StringUtils.isBlank(primaryKey)) {
            return primaryKeys;
        }

        if (PRIMARY_KEY_PLACEHOLDER.equals(primaryKey.trim())) {
            primaryKeys.add(PRIMARY_KEY_PLACEHOLDER);
            return primaryKeys;
        }

        for (String item : primaryKey.split(",")) {
            if (StringUtils.isNotBlank(item)) {
                addDistinct(primaryKeys, item);
            }
        }

        return primaryKeys;
    }

    private Boolean readBooleanObject(Config config, String path) {
        if (config == null || !config.hasPath(path)) {
            return null;
        }

        return JdbcConfigReaders.getBoolean(config, path, false);
    }

    private void addDistinct(List<String> target, String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }

        String cleaned = value.trim();
        if (!target.contains(cleaned)) {
            target.add(cleaned);
        }
    }
}