package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

/**
 * Adapter for historical JDBC rows. The current 1.12.10 ingestion image only
 * has a verified first-class PostgreSQL connector for the deployed JDBC row;
 * unknown JDBC drivers remain explicitly unsupported.
 */
@Component
public class JdbcMetadataConnectorAdapter extends AbstractDatabaseMetadataConnectorAdapter {

    @Override
    public DbType dataSourceType() {
        return DbType.JDBC;
    }

    @Override
    public String openMetadataServiceType() {
        return "Postgres";
    }

    @Override
    public JsonNode databaseServiceRequest(DataSource dataSource, String stableServiceName) {
        String url = connectionUrl(dataSource);
        if (url == null || !url.toLowerCase(java.util.Locale.ROOT).startsWith("jdbc:postgresql:")) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.CONNECTOR_NOT_SUPPORTED,
                    "OpenMetadata 1.12.10 has no verified first-class connector for this JDBC driver");
        }
        ConnectionValues source = connectionValues(dataSource);
        if (isBlank(source.database())) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.SOURCE_CONNECTION_ERROR,
                    "Postgres JDBC requires a database for the OpenMetadata 1.12.10 connection schema");
        }
        ObjectNode root = baseServiceRequest(dataSource, stableServiceName);
        ObjectNode config = root.putObject("connection").putObject("config");
        config.put("type", "Postgres");
        config.put("scheme", "postgresql+psycopg2");
        config.put("username", source.username());
        config.set("authType", passwordAuth(source.password()));
        config.put("hostPort", source.hostPort());
        config.put("database", source.database());
        config.put("supportsMetadataExtraction", true);
        config.put("supportsProfiler", true);
        return root;
    }

    private static String connectionUrl(DataSource dataSource) {
        try {
            JsonNode source = OBJECT_MAPPER.readTree(dataSource.getConnectionParams());
            JsonNode url = source == null ? null : source.get("url");
            return url == null || url.isNull() ? null : url.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
