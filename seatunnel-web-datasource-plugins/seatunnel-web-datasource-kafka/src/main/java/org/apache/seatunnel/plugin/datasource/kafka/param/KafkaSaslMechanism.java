package org.apache.seatunnel.plugin.datasource.kafka.param;

public enum KafkaSaslMechanism {
    PLAIN,
    SCRAM_SHA_256("SCRAM-SHA-256"),
    SCRAM_SHA_512("SCRAM-SHA-512");

    private final String kafkaValue;

    KafkaSaslMechanism() {
        this.kafkaValue = name();
    }

    KafkaSaslMechanism(String kafkaValue) {
        this.kafkaValue = kafkaValue;
    }

    public String kafkaValue() {
        return kafkaValue;
    }
}
