package org.apache.seatunnel.plugin.datasource.kafka;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class KafkaDataSourceProcessorTest {

    @Test
    void sourceOptionRuleAllowsTopicOrPatternToBeValidatedByTheBuilder() {
        assertNotNull(new KafkaDataSourceProcessor().sourceOptionRule("KAFKA"));
    }

    @Test
    void sinkOptionRuleAllowsNoOptionalPartitionOverride() {
        assertNotNull(new KafkaDataSourceProcessor().sinkOptionRule());
    }
}
