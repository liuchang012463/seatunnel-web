package org.apache.seatunnel.web.engine.client.transfrom.impl;

import com.google.auto.service.AutoService;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.engine.client.transfrom.TransformConfigSwitcher;
import org.apache.seatunnel.web.engine.client.transfrom.domain.FieldMapperField;
import org.apache.seatunnel.web.engine.client.transfrom.domain.FieldMapperMapping;
import org.apache.seatunnel.web.engine.client.transfrom.domain.FieldMapperTransformOptions;
import org.apache.seatunnel.web.engine.client.transfrom.domain.Transform;
import org.apache.seatunnel.web.engine.client.transfrom.domain.TransformOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@AutoService(TransformConfigSwitcher.class)
public class FieldMapperTransformSwitcher implements TransformConfigSwitcher {

    @Override
    public Transform getTransform() {
        return Transform.FIELDMAPPER;
    }

    @Override
    public Config transform(TransformOptions options) {
        if (!(options instanceof FieldMapperTransformOptions)) {
            throw new IllegalArgumentException(
                    "Invalid TransformOptions type for FieldMapper: "
                            + (options == null ? "null" : options.getClass().getName())
            );
        }

        FieldMapperTransformOptions transformOptions =
                (FieldMapperTransformOptions) options;

        validate(transformOptions);

        String pluginInput = transformOptions.getEffectivePluginInput();
        String pluginOutput = transformOptions.getEffectivePluginOutput();

        List<FieldMapping> mappings = effectiveMappings(transformOptions);
        if (requiresSqlTransform(mappings)) {
            return buildSqlTransform(pluginInput, pluginOutput, mappings);
        }

        Map<String, Object> fieldMapper = buildFieldMapper(transformOptions);

        log.info(
                "Generating FieldMapper config, plugin_input={}, plugin_output={}, field_count={}",
                pluginInput,
                pluginOutput,
                fieldMapper.size()
        );

        Map<String, Object> fieldMapperConfig = new HashMap<>();
        fieldMapperConfig.put("plugin_input", pluginInput);
        fieldMapperConfig.put("plugin_output", pluginOutput);
        fieldMapperConfig.put("field_mapper", fieldMapper);

        Map<String, Object> root = new HashMap<>();
        root.put("FieldMapper", fieldMapperConfig);

        Config config = ConfigFactory.parseMap(root);

        log.debug("Generated FieldMapper config: {}", config.root().render());

        return config;
    }

    private Map<String, Object> buildFieldMapper(FieldMapperTransformOptions options) {
        Map<String, Object> fieldMapper = new HashMap<>();

        /**
         * New structure:
         * config.mappings: [
         *   {
         *     sourceField,
         *     targetField,
         *     enabled
         *   }
         * ]
         */
        List<FieldMapperMapping> mappings = options.getEffectiveMappings();
        if (CollectionUtils.isNotEmpty(mappings)) {
            for (FieldMapperMapping mapping : mappings) {
                if (Boolean.FALSE.equals(mapping.getEnabled())) {
                    continue;
                }

                fieldMapper.put(
                        mapping.getSourceField(),
                        mapping.getTargetField()
                );
            }

            return fieldMapper;
        }

        /**
         * Old structure:
         * transformColumns: [
         *   {
         *     fieldName,
         *     targetFieldName
         *   }
         * ]
         */
        if (CollectionUtils.isNotEmpty(options.getTransformColumns())) {
            for (FieldMapperField field : options.getTransformColumns()) {
                fieldMapper.put(
                        field.getFieldName(),
                        field.getTargetFieldName()
                );
            }
        }

        return fieldMapper;
    }

    /** One configured mapping row: a source field, its target field and an optional expression. */
    private record FieldMapping(String sourceField, String targetField, String expression, String type) {
    }

    private static List<FieldMapping> effectiveMappings(FieldMapperTransformOptions options) {
        List<FieldMapping> mappings = new ArrayList<>();
        List<FieldMapperMapping> configured = options.getEffectiveMappings();
        if (CollectionUtils.isNotEmpty(configured)) {
            for (FieldMapperMapping mapping : configured) {
                if (Boolean.FALSE.equals(mapping.getEnabled())) {
                    continue;
                }
                mappings.add(new FieldMapping(
                        mapping.getSourceField(),
                        mapping.getTargetField(),
                        StringUtils.trimToNull(mapping.getExpression()),
                        StringUtils.trimToNull(mapping.getTargetType())));
            }
            return mappings;
        }
        if (CollectionUtils.isNotEmpty(options.getTransformColumns())) {
            for (FieldMapperField field : options.getTransformColumns()) {
                mappings.add(new FieldMapping(
                        field.getFieldName(), field.getTargetFieldName(), null, null));
            }
        }
        return mappings;
    }

    /**
     * A FieldMapper renames one input field per output field, so the engine refuses a transform
     * whose output repeats a column name. Several source fields feeding one target field, or a
     * mapping with its own expression, therefore have to run as SQL.
     */
    private static boolean requiresSqlTransform(List<FieldMapping> mappings) {
        Set<String> targets = new LinkedHashSet<>();
        Set<String> sources = new HashSet<>();
        for (FieldMapping mapping : mappings) {
            if (mapping.expression() != null) {
                return true;
            }
            if (!targets.add(mapping.targetField())) {
                return true;
            }
            if (!sources.add(mapping.sourceField())) {
                return true;
            }
        }
        return false;
    }

    private static Config buildSqlTransform(
            String pluginInput, String pluginOutput, List<FieldMapping> mappings) {
        Map<String, List<FieldMapping>> mappingsByTarget = new LinkedHashMap<>();
        for (FieldMapping mapping : mappings) {
            mappingsByTarget
                    .computeIfAbsent(mapping.targetField(), ignored -> new ArrayList<>())
                    .add(mapping);
        }

        validateFallbackTypes(mappingsByTarget);

        List<String> selectItems = new ArrayList<>(mappingsByTarget.size());
        for (Map.Entry<String, List<FieldMapping>> target : mappingsByTarget.entrySet()) {
            selectItems.add(
                    selectExpression(target.getValue()) + " AS " + quoteIdentifier(target.getKey()));
        }

        Map<String, Object> sqlConfig = new LinkedHashMap<>();
        sqlConfig.put("plugin_input", pluginInput);
        sqlConfig.put("plugin_output", pluginOutput);
        sqlConfig.put("query",
                "SELECT " + String.join(", ", selectItems) + " FROM " + quoteIdentifier(pluginInput));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Sql", sqlConfig);

        log.info(
                "Generating Sql transform config for a many-to-one mapping, plugin_input={}, "
                        + "plugin_output={}, target_fields={}",
                pluginInput,
                pluginOutput,
                mappingsByTarget.keySet()
        );

        return ConfigFactory.parseMap(root);
    }

    /**
     * A single source keeps its value; several sources fall back in the configured order, which is
     * the only reading of "many source fields, one target field" that invents no data.
     */
    private static String selectExpression(List<FieldMapping> mappingsForTarget) {
        for (FieldMapping mapping : mappingsForTarget) {
            if (mapping.expression() != null) {
                return mapping.expression();
            }
        }
        if (mappingsForTarget.size() == 1) {
            return quoteIdentifier(mappingsForTarget.get(0).sourceField());
        }
        List<String> sources = new ArrayList<>(mappingsForTarget.size());
        for (FieldMapping mapping : mappingsForTarget) {
            sources.add(quoteIdentifier(mapping.sourceField()));
        }
        return "COALESCE(" + String.join(", ", sources) + ")";
    }

    /**
     * The engine takes a COALESCE's type from its first non-void argument and casts the remaining
     * arguments to it, so a fallback across differently typed columns only fails per row at run
     * time. Reject that at submit time instead; mappings whose declared types agree (or carry no
     * type at all) keep the fallback.
     */
    private static void validateFallbackTypes(Map<String, List<FieldMapping>> mappingsByTarget) {
        for (Map.Entry<String, List<FieldMapping>> entry : mappingsByTarget.entrySet()) {
            List<FieldMapping> mappingsForTarget = entry.getValue();
            if (mappingsForTarget.size() < 2) {
                continue;
            }
            boolean hasExpression = false;
            Set<String> declaredTypes = new LinkedHashSet<>();
            for (FieldMapping mapping : mappingsForTarget) {
                if (mapping.expression() != null) {
                    hasExpression = true;
                    break;
                }
                if (mapping.type() != null) {
                    declaredTypes.add(mapping.type().toUpperCase(Locale.ROOT));
                }
            }
            if (hasExpression || declaredTypes.size() < 2) {
                continue;
            }

            throw new IllegalArgumentException(
                    "Multiple source fields of different types feed the target field '"
                            + entry.getKey() + "': " + declaredTypes
                            + ". Align their types or map them through an expression.");
        }
    }

    /** The engine resolves identifiers between backticks, which also keeps mixed case intact. */
    private static String quoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return "";
        }
        return "`" + identifier.replace("`", "``") + "`";
    }

    private void validate(FieldMapperTransformOptions options) {
        String pluginInput = options.getEffectivePluginInput();
        String pluginOutput = options.getEffectivePluginOutput();

        if (StringUtils.isBlank(pluginInput)) {
            throw new IllegalArgumentException("plugin_input must not be empty");
        }

        if (StringUtils.isBlank(pluginOutput)) {
            throw new IllegalArgumentException("plugin_output must not be empty");
        }

        List<FieldMapperMapping> mappings = options.getEffectiveMappings();

        /**
         * Validate new structure first.
         */
        if (CollectionUtils.isNotEmpty(mappings)) {
            boolean hasEnabled = false;
            for (FieldMapperMapping mapping : mappings) {
                if (Boolean.FALSE.equals(mapping.getEnabled())) {
                    continue;
                }
                hasEnabled = true;

                if (StringUtils.isBlank(mapping.getSourceField())) {
                    throw new IllegalArgumentException("sourceField must not be empty");
                }

                if (StringUtils.isBlank(mapping.getTargetField())) {
                    throw new IllegalArgumentException(
                            "targetField must not be empty for sourceField: "
                                    + mapping.getSourceField()
                    );
                }
            }

            if (!hasEnabled) {
                throw new IllegalArgumentException("at least one enabled mapping is required");
            }

            return;
        }

        /**
         * Validate old structure.
         */
        if (CollectionUtils.isEmpty(options.getTransformColumns())) {
            throw new IllegalArgumentException("mappings must not be empty");
        }

        for (FieldMapperField field : options.getTransformColumns()) {
            if (StringUtils.isBlank(field.getFieldName())) {
                throw new IllegalArgumentException("source field name must not be empty");
            }

            if (StringUtils.isBlank(field.getTargetFieldName())) {
                throw new IllegalArgumentException(
                        "target field name must not be empty for source field: "
                                + field.getFieldName()
                );
            }
        }
    }
}
