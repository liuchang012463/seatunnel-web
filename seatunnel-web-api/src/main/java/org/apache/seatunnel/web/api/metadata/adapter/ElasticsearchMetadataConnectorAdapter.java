package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.api.metadata.MetadataServiceCategory;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Fixed OpenMetadata 1.12.10 ElasticSearch search service adapter. */
@Component
public class ElasticsearchMetadataConnectorAdapter extends AbstractNonDatabaseMetadataConnectorAdapter {

    @Override
    public DbType dataSourceType() {
        return DbType.ELASTICSEARCH;
    }

    @Override
    public String openMetadataServiceType() {
        return "ElasticSearch";
    }

    @Override
    public MetadataServiceCategory serviceCategory() {
        return MetadataServiceCategory.SEARCH;
    }

    @Override
    public JsonNode serviceRequest(DataSource dataSource, String stableServiceName) {
        JsonNode source = rawConnection(dataSource);
        String hostPort = firstHost(source);
        if (isBlank(hostPort)) {
            throw invalidConnectionFailure();
        }

        ObjectNode root = baseServiceRequest(dataSource, stableServiceName);
        ObjectNode config = root.putObject("connection").putObject("config");
        config.put("type", "ElasticSearch");
        config.put("hostPort", hostPort);

        ObjectNode authType = buildAuthType(source);
        if (authType != null) {
            config.set("authType", authType);
        }

        config.put("supportsMetadataExtraction", true);
        return root;
    }

    private ObjectNode buildAuthType(JsonNode source) {
        String authType = connectionText(source, "authType");
        if (isBlank(authType) || "NONE".equals(authType)) {
            return null;
        }
        return switch (authType) {
            case "BASIC" -> basicAuth(source);
            case "API_KEY" -> apiKeyAuth(source);
            case "API_KEY_ENCODED" -> encodedApiKeyAuth(source);
            default -> throw invalidConnectionFailure();
        };
    }

    private ObjectNode basicAuth(JsonNode source) {
        String username = connectionText(source, "username");
        String password = connectionText(source, "password");
        if (isBlank(username) || isBlank(password)) {
            throw invalidConnectionFailure();
        }
        ObjectNode auth = OBJECT_MAPPER.createObjectNode();
        auth.put("username", username);
        auth.put("password", decodePassword(password));
        return auth;
    }

    private ObjectNode apiKeyAuth(JsonNode source) {
        String apiKeyId = connectionText(source, "apiKeyId");
        String apiKey = connectionText(source, "apiKey");
        if (isBlank(apiKeyId) || isBlank(apiKey)) {
            throw invalidConnectionFailure();
        }
        ObjectNode auth = OBJECT_MAPPER.createObjectNode();
        auth.put("apiKeyId", apiKeyId);
        auth.put("apiKey", decodePassword(apiKey));
        return auth;
    }

    private ObjectNode encodedApiKeyAuth(JsonNode source) {
        String apiKeyEncoded = connectionText(source, "apiKeyEncoded");
        if (isBlank(apiKeyEncoded)) {
            throw invalidConnectionFailure();
        }
        ObjectNode auth = OBJECT_MAPPER.createObjectNode();
        auth.put("apiKey", decodePassword(apiKeyEncoded));
        return auth;
    }

    private String firstHost(JsonNode source) {
        JsonNode hostsNode = source.get("hosts");
        if (hostsNode == null || hostsNode.isNull()) {
            return null;
        }
        if (hostsNode.isArray()) {
            for (JsonNode host : hostsNode) {
                if (host != null && !host.isNull() && !host.asText().isBlank()) {
                    return normalizeHost(host.asText().trim());
                }
            }
            return null;
        }
        String hosts = hostsNode.asText();
        if (isBlank(hosts)) {
            return null;
        }
        List<String> parsed = new ArrayList<>();
        String value = hosts.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            try {
                JsonNode array = OBJECT_MAPPER.readTree(value);
                if (array.isArray()) {
                    for (JsonNode host : array) {
                        if (host != null && !host.isNull() && !host.asText().isBlank()) {
                            parsed.add(normalizeHost(host.asText().trim()));
                        }
                    }
                }
            } catch (Exception ignored) {
                // Fall through to comma-separated parsing.
            }
        }
        if (parsed.isEmpty()) {
            for (String part : value.split("[,;\\r\\n]")) {
                if (!part.trim().isBlank()) {
                    parsed.add(normalizeHost(part.trim()));
                }
            }
        }
        return parsed.isEmpty() ? null : parsed.getFirst();
    }

    private static String normalizeHost(String value) {
        if (value.contains("://")) {
            return value;
        }
        return "http://" + value;
    }
}
