package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

/** Fixed OpenMetadata 1.12.10 Doris connection schema adapter. */
@Component
public class DorisMetadataConnectorAdapter extends AbstractDatabaseMetadataConnectorAdapter {

    @Override
    public DbType dataSourceType() {
        return DbType.DORIS;
    }

    @Override
    public String openMetadataServiceType() {
        return "Doris";
    }

    @Override
    public JsonNode databaseServiceRequest(DataSource dataSource, String stableServiceName) {
        ConnectionValues source = connectionValues(dataSource);
        ObjectNode root = baseServiceRequest(dataSource, stableServiceName);
        ObjectNode config = root.putObject("connection").putObject("config");
        config.put("type", "Doris");
        config.put("scheme", "doris");
        config.put("username", source.username());
        // Doris 1.12.10 uses a direct password property, unlike Mysql/Postgres authType.
        if (isBlank(source.password())) {
            throw new org.apache.seatunnel.web.api.metadata.MetadataIntegrationException(
                    org.apache.seatunnel.web.api.metadata.MetadataErrorCode.SOURCE_CONNECTION_ERROR,
                    "Doris connection requires a password");
        }
        config.put("password", source.password());
        config.put("hostPort", source.hostPort());
        if (!isBlank(source.database())) {
            config.put("databaseName", source.database());
        }
        config.put("supportsMetadataExtraction", true);
        config.put("supportsProfiler", true);
        return root;
    }
}
