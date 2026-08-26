package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;

/** Converts one supported SeaTunnel source to fixed OpenMetadata 1.12.10 DTO shapes. */
public interface MetadataConnectorAdapter {

    DbType dataSourceType();

    String openMetadataServiceType();

    JsonNode databaseServiceRequest(DataSource dataSource, String stableServiceName);

    JsonNode metadataPipelineRequest(String pipelineName, String serviceId, String serviceFqn);

    JsonNode profilerPipelineRequest(String pipelineName, String serviceId, String serviceFqn);
}
