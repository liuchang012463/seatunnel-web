package org.apache.seatunnel.web.core.job.handler.multi;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.spi.bean.dto.config.DatapackMultiImportConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds ordinary file-resource, field mapper and sink nodes for one batch job. */
final class DatapackMultiWorkflow {

    private DatapackMultiWorkflow() {}

    static Map<String, Object> assemble(GuideMultiJobContent content) {
        DatapackMultiImportConfig datapack = content.getDatapack();
        if (datapack.getPackageResourceId() == null || datapack.getPackageResourceId() <= 0
                || StringUtils.isBlank(datapack.getPackageName())
                || !StringUtils.defaultString(datapack.getPackageFingerprint()).matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("请重新校验数据包");
        }
        if (datapack.getMappings() == null || datapack.getMappings().isEmpty()) {
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
        for (DatapackMultiImportConfig.CsvMapping row : datapack.getMappings()) {
            validateRow(row);
            if (!selectedFiles.add(row.getFileName()) || !selectedResources.add(row.getFileResourceId())) {
                throw new IllegalArgumentException("重复选择 CSV " + row.getFileName());
            }
            if (!selectedTables.add(row.getTargetTable())) {
                throw new IllegalArgumentException("目标表重复 " + row.getTargetTable());
            }
            ++index;
            String sourceId = "csv_" + index;
            String sinkId = "target_" + index;
            nodes.add(node(sourceId, "source", source(row, sourceId)));
            String upstream = sourceId;
            if (needsMapping(row)) {
                String mapperId = "mapped_" + index;
                List<Map<String, String>> mappings = row.getFieldMapping().entrySet().stream()
                        .map(entry -> Map.of("sourceField", entry.getKey(), "targetField", entry.getValue()))
                        .toList();
                nodes.add(Map.of("id", mapperId, "data", Map.of(
                        "nodeType", "transform", "componentType", "FIELDMAPPER",
                        "config", Map.of("pluginInput", sourceId, "pluginOutput", mapperId, "mappings", mappings))));
                edges.add(edge(sourceId, mapperId));
                upstream = mapperId;
            }
            nodes.add(node(sinkId, "sink", sink(target, row.getTargetTable(), upstream)));
            edges.add(edge(upstream, sinkId));
        }
        return Map.of("nodes", nodes, "edges", edges);
    }

    private static void validateRow(DatapackMultiImportConfig.CsvMapping row) {
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
    }

    private static boolean needsMapping(DatapackMultiImportConfig.CsvMapping row) {
        return row.getFieldMapping().size() != row.getFields().size()
                || row.getFieldMapping().entrySet().stream().anyMatch(entry -> !entry.getKey().equals(entry.getValue()));
    }

    private static Map<String, Object> source(DatapackMultiImportConfig.CsvMapping row, String output) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sourceMode", "FILE_RESOURCE");
        config.put("dbType", "MINIO");
        config.put("pluginName", "S3File");
        config.put("connectorType", "S3File");
        config.put("fileResourceId", row.getFileResourceId());
        config.put("fileFormatType", "csv");
        config.put("encoding", "UTF-8");
        config.put("fieldDelimiter", ",");
        config.put("csvUseHeaderLine", true);
        config.put("quoteChar", "\"");
        config.put("nullFormat", StringUtils.defaultString(row.getNullValue()));
        config.put("schema", Map.of("fields", row.getFields()));
        config.put("pluginOutput", output);
        return config;
    }

    private static Map<String, Object> sink(
            GuideMultiJobContent.WorkflowTargetConfig target, String table, String input) {
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
        config.put("autoCreateTable", false);
        config.put("writeMode", "append");
        config.put("dataSaveMode", "APPEND_DATA");
        config.put("schemaSaveMode", "ERROR_WHEN_SCHEMA_NOT_EXIST");
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
}
