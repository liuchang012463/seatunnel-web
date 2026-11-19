package org.apache.seatunnel.web.core.job.handler.multi;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.spi.bean.dto.config.DatapackMultiImportConfig.CsvFile;
import org.apache.seatunnel.web.spi.bean.dto.config.DatapackMultiImportConfig.CsvMapping;
import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds one S3File branch per selected CSV in the same batch job. */
final class DatapackMultiWorkflow {

    private DatapackMultiWorkflow() {}

    static Map<String, Object> assemble(GuideMultiJobContent content) {
        var datapack = content.getDatapack();
        if (datapack.getPackageResourceId() == null || datapack.getPackageResourceId() <= 0
                || StringUtils.isBlank(datapack.getPackageName())
                || !StringUtils.defaultString(datapack.getPackageFingerprint()).matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("请重新校验数据包");
        }
        boolean autoCreate = datapack.getFiles() != null;
        List<TableImport> tables = autoCreate
                ? datapack.getFiles().stream().map(DatapackMultiWorkflow::fromFile).toList()
                : datapack.getMappings() == null ? List.of()
                : datapack.getMappings().stream().map(DatapackMultiWorkflow::fromSavedMapping).toList();
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("请选择需要导入的 CSV");
        }
        GuideMultiJobContent.WorkflowTargetConfig target = content.getTarget();
        if (target == null || !StringUtils.defaultString(target.getDatasourceId()).matches("[1-9][0-9]*")
                || StringUtils.isAnyBlank(target.getDbType(), target.getPluginName(), target.getDatabase())
                || !StringUtils.equalsAnyIgnoreCase(target.getConnectorType(), "Jdbc", "Doris")) {
            throw new IllegalArgumentException("请选择目标数据源和数据库");
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, String>> edges = new ArrayList<>();
        Set<String> selectedFiles = new HashSet<>();
        Set<Long> selectedResources = new HashSet<>();
        Set<String> selectedTables = new HashSet<>();
        int index = 0;
        for (TableImport row : tables) {
            if (!selectedFiles.add(row.fileName()) || !selectedResources.add(row.fileResourceId())) {
                throw new IllegalArgumentException("重复选择 CSV " + row.fileName());
            }
            if (!selectedTables.add(row.table())) {
                throw new IllegalArgumentException("目标表重复 " + row.table());
            }
            ++index;
            String sourceId = "csv_" + index;
            String sinkId = "target_" + index;
            nodes.add(node(sourceId, "source", source(row, sourceId)));
            String upstream = sourceId;
            if (!row.fieldMapping().isEmpty()) {
                String mapperId = "mapped_" + index;
                List<Map<String, String>> mappings = row.fieldMapping().entrySet().stream()
                        .map(entry -> Map.of("sourceField", entry.getKey(), "targetField", entry.getValue()))
                        .toList();
                nodes.add(Map.of("id", mapperId, "data", Map.of(
                        "nodeType", "transform", "componentType", "FIELDMAPPER",
                        "config", Map.of("pluginInput", sourceId, "pluginOutput", mapperId, "mappings", mappings))));
                edges.add(edge(sourceId, mapperId));
                upstream = mapperId;
            }
            nodes.add(node(sinkId, "sink", sink(target, row.table(), upstream, autoCreate)));
            edges.add(edge(upstream, sinkId));
        }
        return Map.of("nodes", nodes, "edges", edges);
    }

    private static TableImport fromFile(CsvFile file) {
        if (file == null || !StringUtils.endsWithIgnoreCase(file.getFileName(), ".csv")
                || file.getFileResourceId() == null || file.getFileResourceId() <= 0
                || file.getColumnNames() == null || file.getColumnNames().isEmpty()
                || file.getColumnNames().stream().anyMatch(StringUtils::isBlank)
                || new HashSet<>(file.getColumnNames()).size() != file.getColumnNames().size()) {
            throw new IllegalArgumentException("CSV 文件资源或字段结构无效，请重新提取");
        }
        String fileName = file.getFileName();
        String table = fileName.substring(fileName.lastIndexOf('/') + 1, fileName.length() - 4);
        // A dot in a JDBC table option is interpreted as a namespace separator.
        if (StringUtils.isBlank(table) || !table.equals(table.strip()) || table.contains(".")
                || table.contains("${") || table.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("无法使用 CSV 文件名创建同名表 " + fileName);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        file.getColumnNames().forEach(name -> fields.put(name, "string"));
        return new TableImport(fileName, file.getFileResourceId(), fields, file.getNullValue(), table, Map.of());
    }

    private static TableImport fromSavedMapping(CsvMapping row) {
        if (row == null || !StringUtils.endsWithIgnoreCase(row.getFileName(), ".csv")
                || row.getFileResourceId() == null || row.getFileResourceId() <= 0
                || row.getFields() == null || row.getFields().isEmpty()
                || row.getFields().entrySet().stream().anyMatch(field -> StringUtils.isAnyBlank(
                        field.getKey(), field.getValue()))) {
            throw new IllegalArgumentException("CSV 文件资源或字段结构无效，请重新提取");
        }
        if (StringUtils.isBlank(row.getTargetTable()) || row.getTargetTable().contains("${")) {
            throw new IllegalArgumentException("请选择 " + row.getFileName() + " 的目标表");
        }
        Map<String, String> mapping = row.getFieldMapping();
        if (mapping == null || mapping.isEmpty() || !row.getFields().keySet().containsAll(mapping.keySet())
                || mapping.values().stream().anyMatch(StringUtils::isBlank)
                || new HashSet<>(mapping.values()).size() != mapping.size()) {
            throw new IllegalArgumentException("请检查 " + row.getFileName() + " 的字段映射");
        }
        boolean identity = mapping.size() == row.getFields().size()
                && mapping.entrySet().stream().allMatch(entry -> entry.getKey().equals(entry.getValue()));
        return new TableImport(row.getFileName(), row.getFileResourceId(), row.getFields(), row.getNullValue(),
                row.getTargetTable(), identity ? Map.of() : mapping);
    }

    private static Map<String, Object> source(TableImport row, String output) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sourceMode", "FILE_RESOURCE");
        config.put("dbType", "MINIO");
        config.put("pluginName", "S3File");
        config.put("connectorType", "S3File");
        config.put("fileResourceId", row.fileResourceId());
        config.put("fileFormatType", "csv");
        config.put("encoding", "UTF-8");
        config.put("fieldDelimiter", ",");
        config.put("csvUseHeaderLine", true);
        config.put("quoteChar", "\"");
        config.put("nullFormat", StringUtils.defaultString(row.nullValue()));
        config.put("schema", Map.of("fields", row.fields()));
        config.put("pluginOutput", output);
        return config;
    }

    private static Map<String, Object> sink(
            GuideMultiJobContent.WorkflowTargetConfig target, String table, String input, boolean autoCreate) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dataSourceId", target.getDatasourceId());
        config.put("dbType", target.getDbType());
        config.put("pluginName", target.getPluginName());
        config.put("connectorType", target.getConnectorType());
        config.put("database", target.getDatabase());
        if (StringUtils.isNotBlank(target.getSchemaName())) {
            config.put("schemaName", target.getSchemaName());
        }
        config.put("table", table);
        config.put("targetMode", "table");
        config.put("autoCreateTable", autoCreate);
        config.put("writeMode", "append");
        config.put("dataSaveMode", "APPEND_DATA");
        config.put("schemaSaveMode", autoCreate ? "CREATE_SCHEMA_WHEN_NOT_EXIST" : "ERROR_WHEN_SCHEMA_NOT_EXIST");
        if (autoCreate) {
            config.put("fieldIde", "ORIGINAL");
            // Doris reads the engine option names directly.
            config.put("schema_save_mode", "CREATE_SCHEMA_WHEN_NOT_EXIST");
            config.put("data_save_mode", "APPEND_DATA");
        }
        config.put("enableUpsert", false);
        config.put("pluginInput", input);
        return config;
    }

    private static Map<String, Object> node(String id, String type, Map<String, Object> config) {
        // Connector selection reads the same node metadata as the single-table canvas.
        return Map.of("id", id, "data", Map.of(
                "nodeType", type,
                "dbType", config.get("dbType"),
                "connectorType", config.get("connectorType"),
                "pluginName", config.get("pluginName"),
                "config", config));
    }

    private static Map<String, String> edge(String source, String target) {
        return Map.of("id", source + "-" + target, "source", source, "target", target);
    }

    private record TableImport(String fileName, Long fileResourceId, Map<String, String> fields,
                               String nullValue, String table, Map<String, String> fieldMapping) {}
}
