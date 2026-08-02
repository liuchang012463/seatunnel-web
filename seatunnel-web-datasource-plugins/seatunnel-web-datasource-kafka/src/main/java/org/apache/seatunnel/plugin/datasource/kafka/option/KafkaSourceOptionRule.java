package org.apache.seatunnel.plugin.datasource.kafka.option;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRule;
import org.apache.seatunnel.web.common.config.OptionRule;

import static org.apache.seatunnel.plugin.datasource.kafka.option.KafkaOptions.*;

@AutoService(SourceOptionRule.class)
public class KafkaSourceOptionRule implements SourceOptionRule {

    @Override
    public OptionRule sourceOptionRule() {
        return OptionRule.builder()
                .required(BOOTSTRAP_SERVERS)
                .optional(TOPIC, PATTERN, CONSUMER_GROUP, START_MODE, START_OFFSETS,
                        START_TIMESTAMP, END_TIMESTAMP, COMMIT_ON_CHECKPOINT, POLL_TIMEOUT,
                        FORMAT, KAFKA_CONFIG, SCHEMA, FIELD_DELIMITER)
                // OptionRule.exclusive requires exactly one option and rejects a valid
                // rule declaration when both alternatives are optional. KafkaHoconBuilder
                // validates that exactly one of topic or pattern is configured.
                .build();
    }

    @Override
    public String pluginName() {
        return "KAFKA";
    }
}
