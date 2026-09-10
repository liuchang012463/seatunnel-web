package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.api.metadata.MetadataServiceCategory;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

/** OpenMetadata 1.12.10 Rest ApiService adapter for HTTP sources. */
@Component
public class HttpMetadataConnectorAdapter extends AbstractNonDatabaseMetadataConnectorAdapter {

    @Override
    public DbType dataSourceType() {
        return DbType.HTTP;
    }

    @Override
    public MetadataServiceCategory serviceCategory() {
        return MetadataServiceCategory.API;
    }

    @Override
    public String openMetadataServiceType() {
        return "Rest";
    }

    @Override
    public JsonNode serviceRequest(DataSource dataSource, String stableServiceName) {
        JsonNode raw = rawConnection(dataSource);
        String openApiSpecUrl = connectionText(raw, "openApiSpecUrl");
        if (isBlank(openApiSpecUrl)) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.SOURCE_CONNECTION_ERROR,
                    "OpenAPI spec URL is required for OpenMetadata metadata extraction");
        }

        ObjectNode root = baseServiceRequest(dataSource, stableServiceName);
        ObjectNode config = root.putObject("connection").putObject("config");
        config.put("type", "Rest");
        config.putObject("openAPISchemaConnection").put("openAPISchemaURL", openApiSpecUrl);

        String bearerToken = connectionText(raw, "bearerToken");
        if (!isBlank(bearerToken)) {
            config.put("token", decodePassword(bearerToken));
        }
        config.put("supportsMetadataExtraction", true);
        return root;
    }
}
