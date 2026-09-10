package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

/** OpenMetadata 1.12.10 S3-compatible StorageService adapter for MinIO. */
@Component
public class MinioMetadataConnectorAdapter extends AbstractS3CompatibleMetadataConnectorAdapter {

    @Override
    public DbType dataSourceType() {
        return DbType.MINIO;
    }

    @Override
    protected void populateAwsCredentials(ObjectNode awsConfig, JsonNode raw) {
        populateStaticAwsCredentials(awsConfig, raw);
    }
}
