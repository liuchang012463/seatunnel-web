package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

/** OpenMetadata 1.12.10 S3 StorageService adapter (prefix-level metadata only). */
@Component
public class S3MetadataConnectorAdapter extends AbstractS3CompatibleMetadataConnectorAdapter {

    @Override
    public DbType dataSourceType() {
        return DbType.S3;
    }

    @Override
    protected void populateAwsCredentials(ObjectNode awsConfig, JsonNode raw) {
        String credentialMode = text(raw, "credentialMode");
        if ("INSTANCE_PROFILE".equalsIgnoreCase(credentialMode)) {
            awsConfig.put("enabled", true);
            return;
        }
        populateStaticAwsCredentials(awsConfig, raw);
    }
}
