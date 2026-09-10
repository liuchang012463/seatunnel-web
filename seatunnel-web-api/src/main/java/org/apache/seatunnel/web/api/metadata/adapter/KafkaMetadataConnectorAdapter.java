package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.api.metadata.MetadataServiceCategory;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

/** Fixed OpenMetadata 1.12.10 Kafka messaging service adapter. */
@Component
public class KafkaMetadataConnectorAdapter extends AbstractNonDatabaseMetadataConnectorAdapter {

    @Override
    public DbType dataSourceType() {
        return DbType.KAFKA;
    }

    @Override
    public String openMetadataServiceType() {
        return "Kafka";
    }

    @Override
    public MetadataServiceCategory serviceCategory() {
        return MetadataServiceCategory.MESSAGING;
    }

    @Override
    public JsonNode serviceRequest(DataSource dataSource, String stableServiceName) {
        JsonNode source = rawConnection(dataSource);
        String schemaRegistryUrl = connectionText(source, "schemaRegistryUrl");
        if (isBlank(schemaRegistryUrl)) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.SOURCE_CONNECTION_ERROR,
                    "Kafka metadata extraction requires schemaRegistryUrl");
        }
        String bootstrapServers = connectionText(source, "bootstrapServers");
        if (isBlank(bootstrapServers)) {
            throw invalidConnectionFailure();
        }

        ObjectNode root = baseServiceRequest(dataSource, stableServiceName);
        ObjectNode config = root.putObject("connection").putObject("config");
        config.put("type", "Kafka");
        config.put("bootstrapServers", bootstrapServers);
        config.put("schemaRegistryURL", schemaRegistryUrl);

        String securityProtocol = connectionText(source, "securityProtocol");
        if (!isBlank(securityProtocol)) {
            config.put("securityProtocol", securityProtocol);
        }

        String saslMechanism = connectionText(source, "saslMechanism");
        if (!isBlank(saslMechanism)) {
            config.put("saslMechanism", normalizeSaslMechanism(saslMechanism));
        }

        String username = connectionText(source, "username");
        if (!isBlank(username)) {
            config.put("saslUsername", username);
        }
        String password = connectionText(source, "password");
        if (!isBlank(password)) {
            config.put("saslPassword", decodePassword(password));
        }

        JsonNode kafkaConfig = source.get("kafkaConfig");
        if (kafkaConfig != null && kafkaConfig.isObject() && !kafkaConfig.isEmpty()) {
            config.set("consumerConfig", kafkaConfig.deepCopy());
        }

        config.put("supportsMetadataExtraction", true);
        return root;
    }

    private static String normalizeSaslMechanism(String value) {
        return switch (value) {
            case "SCRAM_SHA_256" -> "SCRAM-SHA-256";
            case "SCRAM_SHA_512" -> "SCRAM-SHA-512";
            default -> value;
        };
    }
}
