package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.api.metadata.MetadataServiceCategory;
import org.apache.seatunnel.web.api.metadata.OmResourceType;
import org.apache.seatunnel.web.dao.entity.DataSource;

import java.util.List;

/** Shared OpenMetadata 2.0.4 S3 StorageService mapping for S3-compatible sources. */
abstract class AbstractS3CompatibleMetadataConnectorAdapter extends AbstractNonDatabaseMetadataConnectorAdapter {

    @Override
    public MetadataServiceCategory serviceCategory() {
        return MetadataServiceCategory.STORAGE;
    }

    @Override
    public List<OmResourceType> resourceTypes() {
        return List.of(OmResourceType.CONTAINER);
    }

    @Override
    public boolean supportsSampleData() {
        return true;
    }

    /**
     * The storage metadata pipeline has no sample-data flag: container rows are only
     * collected by the auto-classification agent, so sample collection needs its own
     * pipeline.
     */
    @Override
    public boolean collectsSampleDataViaAutoClassification() {
        return true;
    }

    @Override
    public boolean supportsStorageManifest() {
        return true;
    }

    /**
     * Injects the operator's manifest as the pipeline's {@code defaultManifest}. It is the
     * same JSON a bucket-level {@code openmetadata.json} would hold, which is what turns a
     * plain container into a structured one with a data model.
     */
    @Override
    public JsonNode metadataPipelineRequest(
            DataSource dataSource,
            String pipelineName,
            String serviceId,
            String serviceFqn,
            MetadataSyncOptions options) {
        ObjectNode request = (ObjectNode) metadataPipelineRequest(pipelineName, serviceId, serviceFqn);
        if (options != null && options.hasStorageManifest()) {
            request.withObject("/sourceConfig/config")
                    .put("defaultManifest", options.storageManifest());
        }
        return request;
    }

    /**
     * Sample-only auto-classification pipeline. PII classification stays disabled: the
     * operator asked for sample data, not for tag writes.
     */
    @Override
    public JsonNode autoClassificationPipelineRequest(
            String pipelineName, String serviceId, String serviceFqn, MetadataSyncOptions options) {
        ObjectNode config = OBJECT_MAPPER.createObjectNode();
        config.put("type", "AutoClassification");
        config.put("storeSampleData", options != null && options.sampleDataEnabled());
        config.put("enableAutoClassification", false);
        return pipelineRequest(
                pipelineName, serviceId, serviceFqn, "autoClassification", config, null);
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
