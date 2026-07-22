package org.apache.seatunnel.plugin.datasource.kafka.option;

import org.apache.seatunnel.web.common.config.Option;
import org.apache.seatunnel.web.common.config.Options;

import java.util.List;
import java.util.Map;

public final class KafkaOptions {

    public static final Option<String> BOOTSTRAP_SERVERS = Options.key("bootstrap.servers").stringType().noDefaultValue();
    public static final Option<String> TOPIC = Options.key("topic").stringType().noDefaultValue();
    public static final Option<String> PATTERN = Options.key("pattern").stringType().noDefaultValue();
    public static final Option<String> CONSUMER_GROUP = Options.key("consumer.group").stringType().noDefaultValue();
    public static final Option<String> START_MODE = Options.key("start_mode").stringType().defaultValue("group_offsets");
    public static final Option<String> START_OFFSETS = Options.key("start_mode.offsets").stringType().noDefaultValue();
    public static final Option<Long> START_TIMESTAMP = Options.key("start_mode.timestamp").longType().noDefaultValue();
    public static final Option<Long> END_TIMESTAMP = Options.key("start_mode.end_timestamp").longType().noDefaultValue();
    public static final Option<Boolean> COMMIT_ON_CHECKPOINT = Options.key("commit_on_checkpoint").booleanType().defaultValue(true);
    public static final Option<Long> POLL_TIMEOUT = Options.key("poll.timeout").longType().noDefaultValue();
    public static final Option<String> FORMAT = Options.key("format").stringType().defaultValue("json");
    public static final Option<Map<String, String>> KAFKA_CONFIG = Options.key("kafka.config").mapType().noDefaultValue();
    public static final Option<String> SCHEMA = Options.key("schema").stringType().noDefaultValue();
    public static final Option<String> FIELD_DELIMITER = Options.key("field_delimiter").stringType().noDefaultValue();
    public static final Option<String> SEMANTICS = Options.key("semantics").stringType().defaultValue("NON");
    public static final Option<String> TRANSACTION_PREFIX = Options.key("transaction_prefix").stringType().noDefaultValue();
    public static final Option<Integer> PARTITION = Options.key("partition").intType().noDefaultValue();
    public static final Option<List<String>> PARTITION_KEY_FIELDS = Options.key("partition_key_fields").listType().noDefaultValue();

    private KafkaOptions() {}
}
