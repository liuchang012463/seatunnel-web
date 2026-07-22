package org.apache.seatunnel.plugin.datasource.kafka.builder;

import com.google.auto.service.AutoService;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.kafka.param.KafkaClientProperties;
import org.apache.seatunnel.plugin.datasource.kafka.param.KafkaConnectionParam;
import org.apache.seatunnel.plugin.datasource.kafka.param.KafkaConnectionParamConverter;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@AutoService(DataSourceHoconBuilder.class)
public class KafkaHoconBuilder implements DataSourceHoconBuilder {

    private static final Set<String> INTERNAL_KEYS = new HashSet<>(Arrays.asList(
            "dataSourceId", "datasourceId", "dbType", "connectorType", "pluginName",
            "nodeId", "nodeName", "extraParams", "kafkaConfig"));
    private static final Set<String> SOURCE_RESERVED = new HashSet<>(Arrays.asList(
            "bootstrap.servers", "topic", "pattern", "consumer.group", "start_mode",
            "start_mode.offsets", "start_mode.timestamp", "start_mode.end_timestamp",
            "commit_on_checkpoint", "poll.timeout", "format", "schema", "field_delimiter",
            "kafka.config"));
    private static final Set<String> SINK_RESERVED = new HashSet<>(Arrays.asList(
            "bootstrap.servers", "topic", "format", "semantics", "transaction_prefix",
            "partition", "partition_key_fields", "kafka.config"));

    @Override
    public String pluginName() {
        return "KAFKA";
    }

    @Override
    public Config buildSourceHocon(HoconBuildContext context) {
        Map<String, Object> node = nodeValues(context);
        Map<String, Object> result = baseConfig(context, node);
        appendExtraParams(result, node, SOURCE_RESERVED);
        putStructured(result, node, "topic", "topic");
        putStructured(result, node, "pattern", "pattern");
        putStructured(result, node, "consumerGroup", "consumer.group");
        putStructured(result, node, "startMode", "start_mode");
        putStructured(result, node, "startModeOffsets", "start_mode.offsets");
        putStructured(result, node, "startModeTimestamp", "start_mode.timestamp");
        putStructured(result, node, "startModeEndTimestamp", "start_mode.end_timestamp");
        putStructured(result, node, "commitOnCheckpoint", "commit_on_checkpoint");
        putStructured(result, node, "pollTimeout", "poll.timeout");
        putStructured(result, node, "format", "format");
        putStructured(result, node, "schema", "schema");
        putStructured(result, node, "fieldDelimiter", "field_delimiter");
        result.putIfAbsent("format", "json");
        result.putIfAbsent("start_mode", "group_offsets");
        result.putIfAbsent("commit_on_checkpoint", true);
        validateSource(result);
        return toConfig(result);
    }

    @Override
    public Config buildSinkHocon(HoconBuildContext context) {
        Map<String, Object> node = nodeValues(context);
        Map<String, Object> result = baseConfig(context, node);
        appendExtraParams(result, node, SINK_RESERVED);
        putStructured(result, node, "topic", "topic");
        putStructured(result, node, "format", "format");
        putStructured(result, node, "semantics", "semantics");
        putStructured(result, node, "transactionPrefix", "transaction_prefix");
        putStructured(result, node, "partition", "partition");
        putStructured(result, node, "partitionKeyFields", "partition_key_fields");
        result.putIfAbsent("format", "json");
        result.putIfAbsent("semantics", "NON");
        validateSink(result);
        return toConfig(result);
    }

    private Map<String, Object> baseConfig(HoconBuildContext context, Map<String, Object> node) {
        KafkaConnectionParam param = new KafkaConnectionParamConverter()
                .createConnectionParams(context.getConnectionParam());
        Map<String, Object> clientProperties = KafkaClientProperties.build(param);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bootstrap.servers", param.getBootstrapServers());

        Map<String, Object> kafkaConfig = new LinkedHashMap<>();
        clientProperties.forEach((key, value) -> {
            if (!"bootstrap.servers".equals(key)) {
                kafkaConfig.put(key, value);
            }
        });
        kafkaConfig.putAll(flattenKafkaConfig(asMap(node.get("kafkaConfig"))));
        if (!kafkaConfig.isEmpty()) {
            result.put("kafka.config", quoteDottedKeys(kafkaConfig));
        }
        return result;
    }

    private Map<String, Object> nodeValues(HoconBuildContext context) {
        if (context.getNodeConfig() == null) {
            return Collections.emptyMap();
        }
        return new LinkedHashMap<>(context.getNodeConfig().root().unwrapped());
    }

    private void appendExtraParams(Map<String, Object> target, Map<String, Object> node, Set<String> reserved) {
        asMap(node.get("extraParams")).forEach((key, value) -> {
            if (!INTERNAL_KEYS.contains(key) && !reserved.contains(key)) {
                target.put(key, value);
            }
        });
    }

    private void putStructured(Map<String, Object> target, Map<String, Object> node, String field, String hoconKey) {
        Object value = node.get(field);
        if (value != null && (!(value instanceof String) || StringUtils.isNotBlank((String) value))) {
            target.put(hoconKey, value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? new LinkedHashMap<>((Map<String, Object>) value) : Collections.emptyMap();
    }

    private void validateSource(Map<String, Object> config) {
        boolean hasTopic = hasText(config.get("topic"));
        boolean hasPattern = hasText(config.get("pattern"));
        if (hasTopic == hasPattern) {
            throw new IllegalArgumentException("Kafka Source must configure exactly one of topic or pattern");
        }
        String startMode = String.valueOf(config.get("start_mode"));
        if ("specific_offsets".equals(startMode) && config.get("start_mode.offsets") == null) {
            throw new IllegalArgumentException("startModeOffsets is required for specific_offsets");
        }
        if ("timestamp".equals(startMode) && config.get("start_mode.timestamp") == null) {
            throw new IllegalArgumentException("startModeTimestamp is required for timestamp mode");
        }
        if ("text".equalsIgnoreCase(String.valueOf(config.get("format")))
                && !hasText(config.get("field_delimiter"))) {
            throw new IllegalArgumentException("fieldDelimiter is required when Kafka format is text");
        }
    }

    private void validateSink(Map<String, Object> config) {
        if (!hasText(config.get("topic"))) {
            throw new IllegalArgumentException("Kafka Sink topic cannot be empty");
        }
        if ("EXACTLY_ONCE".equals(String.valueOf(config.get("semantics")))
                && !hasText(config.get("transaction_prefix"))) {
            throw new IllegalArgumentException("transactionPrefix is required for EXACTLY_ONCE");
        }
        if (config.get("partition") != null && config.get("partition_key_fields") != null) {
            throw new IllegalArgumentException("partition and partitionKeyFields are mutually exclusive");
        }
    }

    private boolean hasText(Object value) {
        return value != null && StringUtils.isNotBlank(String.valueOf(value));
    }

    private Config toConfig(Map<String, Object> values) {
        Map<String, Object> hoconValues = new LinkedHashMap<>();
        values.forEach((key, value) -> hoconValues.put(
                key.startsWith("start_mode.") ? "\"" + key + "\"" : key,
                value));
        return ConfigFactory.parseMap(hoconValues);
    }

    private Map<String, Object> quoteDottedKeys(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(
                key.contains(".") ? "\"" + key + "\"" : key,
                value));
        return result;
    }

    private Map<String, Object> flattenKafkaConfig(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        flattenKafkaConfig("", values, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void flattenKafkaConfig(String prefix, Map<String, Object> values, Map<String, Object> target) {
        values.forEach((key, value) -> {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map) {
                flattenKafkaConfig(fullKey, (Map<String, Object>) value, target);
            } else {
                target.put(fullKey, value);
            }
        });
    }
}
