package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.api.metadata.MetadataServiceCategory;
import org.apache.seatunnel.web.dao.entity.DataSource;

/** Shared OpenMetadata 1.12.10 S3 StorageService mapping for S3-compatible sources. */
abstract class AbstractS3CompatibleMetadataConnectorAdapter extends AbstractNonDatabaseMetadataConnectorAdapter {

    @Override
    public MetadataServiceCategory serviceCategory() {
        return MetadataServiceCategory.STORAGE;
    }

    @Override
    public String openMetadataServiceType() {
        return "S3";
    }

    @Override
    public JsonNode serviceRequest(DataSource dataSource, String stableServiceName) {
        JsonNode raw = rawConnection(dataSource);
        String endpoint = requiredText(raw, "endpoint");
        String region = requiredText(raw, "region");
        String bucket = requiredText(raw, "bucket");

        ObjectNode root = baseServiceRequest(dataSource, stableServiceName);
        ObjectNode config = root.putObject("connection").putObject("config");
        config.put("type", "S3");

        ObjectNode awsConfig = config.putObject("awsConfig");
        awsConfig.put("awsRegion", region);
        awsConfig.put("endPointURL", endpoint);
        populateAwsCredentials(awsConfig, raw);

        config.withArray("bucketNames").add(bucket);
        applyContainerFilterPattern(config, connectionText(raw, "basePath"));
        config.put("supportsMetadataExtraction", true);
        return root;
    }

    protected abstract void populateAwsCredentials(ObjectNode awsConfig, JsonNode raw);

    protected void populateStaticAwsCredentials(ObjectNode awsConfig, JsonNode raw) {
        String accessKey = requiredText(raw, "accessKey");
        String secretKey = requiredText(raw, "secretKey");
        awsConfig.put("awsAccessKeyId", accessKey);
        awsConfig.put("awsSecretAccessKey", decodePassword(secretKey));
    }

    protected static String requiredText(JsonNode source, String field) {
        String value = text(source, field);
        if (isBlank(value)) {
            throw invalidConnectionFailure();
        }
        return value;
    }

    protected static String text(JsonNode source, String field) {
        JsonNode value = source == null ? null : source.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    protected static void applyContainerFilterPattern(ObjectNode config, String basePath) {
        String prefix = normalizePrefix(basePath);
        if (isBlank(prefix)) {
            return;
        }
        ObjectNode filter = OBJECT_MAPPER.createObjectNode();
        filter.withArray("includes").add("^" + pythonRegexLiteral(prefix) + "(/.*)?$");
        filter.set("excludes", OBJECT_MAPPER.createArrayNode());
        config.set("containerFilterPattern", filter);
    }

    private static String normalizePrefix(String basePath) {
        if (isBlank(basePath) || "/".equals(basePath.trim())) {
            return null;
        }
        String normalized = basePath.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private static String pythonRegexLiteral(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if ("\\.[]{}()*+-?^$|".indexOf(current) >= 0) {
                escaped.append('\\');
            }
            escaped.append(current);
        }
        return escaped.toString();
    }
}
