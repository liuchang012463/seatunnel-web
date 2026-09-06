package org.apache.seatunnel.web.core.job.bridge;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.spi.bean.dto.command.GuideSingleJobContentCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prefills the existing field mapper when a MANAGED ODS table is used by an
 * exact single-table job.  The persisted mapping names are copied verbatim;
 * in particular, no lower-case or case-insensitive normalization is applied.
 */
@Component
public class LakeManagedMappingPrefillService {

    private static final String KEY_NODES = "nodes";
    private static final String KEY_DATA = "data";
    private static final String KEY_CONFIG = "config";
    private static final String KEY_NODE_TYPE = "nodeType";
    private static final String KEY_COMPONENT_TYPE = "componentType";
    private static final String KEY_SINK = "sink";
    private static final String KEY_TRANSFORM = "transform";
    private static final String KEY_FIELDMAPPER = "FIELDMAPPER";

    @Resource
    private LakeOdsTableMappingDao tableMappingDao;

    /** Mutates the structured workflow only when a matching MANAGED mapping exists. */
    public void prefill(JobDefinitionSaveCommand command) {
        if (command == null
                || (command.getMode() != JobDefinitionMode.GUIDE_SINGLE
                && command.getMode() != JobDefinitionMode.GUIDE_SINGLE_INCREMENTAL)
                || !(command instanceof GuideSingleJobContentCommand singleCommand)) {
            return;
        }

        Long bindingId = LakeJobBindingResolver.resolve(command);
        if (bindingId == null || tableMappingDao == null) {
            return;
        }

        Map<String, Object> workflow = singleCommand.getWorkflow();
        List<Map<String, Object>> nodes = nodes(workflow);
        Map<String, Object> sinkConfig = null;
        List<Map<String, Object>> fieldMapperConfigs = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            Map<String, Object> data = map(node.get(KEY_DATA));
            String nodeType = text(data.get(KEY_NODE_TYPE));
            Map<String, Object> config = map(data.get(KEY_CONFIG));
            if (config.isEmpty()) {
                config = data;
            }
            if (KEY_SINK.equalsIgnoreCase(nodeType)) {
                sinkConfig = config;
            } else if (KEY_TRANSFORM.equalsIgnoreCase(nodeType)
                    && KEY_FIELDMAPPER.equalsIgnoreCase(text(data.get(KEY_COMPONENT_TYPE)))) {
                fieldMapperConfigs.add(config);
            }
        }

        if (sinkConfig == null) {
            return;
        }

        String targetTable = firstText(
                sinkConfig.get("targetTableName"),
                sinkConfig.get("target_table_name"),
                sinkConfig.get("table"),
                sinkConfig.get("table_path"));
        if (StringUtils.isBlank(targetTable)) {
            return;
        }

        LakeOdsTableMapping mapping = tableMappingDao.queryByBindingIdAndTargetTable(
                bindingId, targetTable.trim());
        if (mapping == null || mapping.getManagementLevel() != LakeManagementLevel.MANAGED
                || StringUtils.isBlank(mapping.getFieldMappingsJson())) {
            return;
        }

        List<Map<String, Object>> mappings = readMappings(mapping.getFieldMappingsJson());
        if (mappings.isEmpty()) {
            return;
        }

        boolean applied = false;
        for (Map<String, Object> config : fieldMapperConfigs) {
            if (isEmptyMappings(config.get("mappings"))) {
                config.put("mappings", mappings);
                applied = true;
            }
        }

        // A direct sink mappings array is used by older structured payloads.
        // Preserve a non-empty user mapping and only fill an empty one.
        if (!applied && isEmptyMappings(sinkConfig.get("mappings"))) {
            sinkConfig.put("mappings", mappings);
        }
    }

    private List<Map<String, Object>> readMappings(String json) {
        try {
            List<Map<String, Object>> parsed = JSONUtils.parseObject(
                    json, new TypeReference<List<Map<String, Object>>>() {
                    });
            if (parsed == null) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> item : parsed) {
                if (item == null) {
                    continue;
                }
                Map<String, Object> normalized = new LinkedHashMap<>();
                copyIfPresent(item, normalized, "sourceField");
                copyIfPresent(item, normalized, "targetField");
                copyIfPresent(item, normalized, "targetType");
                if (!normalized.isEmpty()) {
                    result.add(normalized);
                }
            }
            return result;
        } catch (RuntimeException e) {
            // A malformed local mapping must not corrupt an otherwise valid
            // job payload.  The managed-table API validates this JSON at write
            // time, so this is only a compatibility guard for legacy rows.
            return Collections.emptyList();
        }
    }

    private void copyIfPresent(Map<String, Object> source,
                               Map<String, Object> target,
                               String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private boolean isEmptyMappings(Object value) {
        return value == null || value instanceof List<?> list && list.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodes(Map<String, Object> workflow) {
        if (workflow == null || !(workflow.get(KEY_NODES) instanceof List<?> rawNodes)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object rawNode : rawNodes) {
            if (rawNode instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) (Map<?, ?>) map);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        return (Map<String, Object>) (Map<?, ?>) map;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            if (StringUtils.isNotBlank(text(value))) {
                return text(value);
            }
        }
        return "";
    }
}
