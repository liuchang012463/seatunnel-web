package org.apache.seatunnel.web.api.metadata.openlineage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka delivery configuration for the OpenLineage lineage bridge.
 *
 * <p>
 * The SASL user defaults to the deployment's shared bridge account, matching
 * {@code .env.example}; the password has no default and has to come from the environment.
 * </p>
 */
@ConfigurationProperties(prefix = "lineage-bridge")
public class LineageBridgeProperties {

    private final Kafka kafka = new Kafka();

    public Kafka getKafka() {
        return kafka;
    }

    /** Kafka producer client properties for the {@code OpenLineageKafkaBridge}. */
    public static class Kafka {

        /**
         * Master switch, enabled by default. {@code false} leaves no producer, no scheduler and no
         * listener behind.
         */
        private boolean enabled = true;

        /** Comma separated bootstrap servers; blank with enabled=true drops events with an error log. */
        private String bootstrapServers = "";

        /** Topic the asset project consumes the lineage events from. */
        private String sourceTopic = "meta_lineage_topic";

        private String saslUsername = "kafka-admin";

        private String saslPassword = "";

        /** SASL_PLAINTEXT / SASL_SSL / SSL / PLAINTEXT per the deployment's Kafka setup. */
        private String securityProtocol = "SASL_PLAINTEXT";

        /** PLAIN or SCRAM-SHA-256 / SCRAM-SHA-512. */
        private String saslMechanism = "PLAIN";

        private String clientId = "seatunnel-web-lineage-bridge";

        /**
         * Creates the topic when the broker does not have it yet. A broker with automatic topic
         * creation turned off would otherwise drop every lineage event.
         */
        private boolean autoCreateTopic = true;

        /** Timeout of the topic administration call, in milliseconds. */
        private int adminTimeoutMs = 10000;

        /**
         * Timeout of one event delivery, in milliseconds. The bridge waits for the broker
         * acknowledgement so the caller knows whether a terminal event may be forgotten.
         */
        private int sendTimeoutMs = 5000;

        /**
         * How long the producer waits for the topic metadata, in milliseconds. An unreachable
         * broker blocks the send for this long, so it bounds the damage a broker outage does
         * to the lineage track.
         */
        private int maxBlockMs = 10000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getSourceTopic() {
            return sourceTopic;
        }

        public void setSourceTopic(String sourceTopic) {
            this.sourceTopic = sourceTopic;
        }

        public String getSaslUsername() {
            return saslUsername;
        }

        public void setSaslUsername(String saslUsername) {
            this.saslUsername = saslUsername;
        }

        public String getSaslPassword() {
            return saslPassword;
        }

        public void setSaslPassword(String saslPassword) {
            this.saslPassword = saslPassword;
        }

        public String getSecurityProtocol() {
            return securityProtocol;
        }

        public void setSecurityProtocol(String securityProtocol) {
            this.securityProtocol = securityProtocol;
        }

        public String getSaslMechanism() {
            return saslMechanism;
        }

        public void setSaslMechanism(String saslMechanism) {
            this.saslMechanism = saslMechanism;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public boolean isAutoCreateTopic() {
            return autoCreateTopic;
        }

        public void setAutoCreateTopic(boolean autoCreateTopic) {
            this.autoCreateTopic = autoCreateTopic;
        }

        public int getAdminTimeoutMs() {
            return adminTimeoutMs;
        }

        public void setAdminTimeoutMs(int adminTimeoutMs) {
            this.adminTimeoutMs = adminTimeoutMs;
        }

        public int getSendTimeoutMs() {
            return sendTimeoutMs;
        }

        public void setSendTimeoutMs(int sendTimeoutMs) {
            this.sendTimeoutMs = sendTimeoutMs;
        }

        public int getMaxBlockMs() {
            return maxBlockMs;
        }

        public void setMaxBlockMs(int maxBlockMs) {
            this.maxBlockMs = maxBlockMs;
        }
    }
}
