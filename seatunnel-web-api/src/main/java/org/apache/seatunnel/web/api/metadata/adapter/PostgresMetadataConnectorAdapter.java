package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

/** Fixed OpenMetadata 1.12.10 Postgres connection schema adapter. */
@Component
public class PostgresMetadataConnectorAdapter extends AbstractDatabaseMetadataConnectorAdapter {

    @Override
    public DbType dataSourceType() {
        return DbType.POSTGRE_SQL;
    }

    @Override
    public String openMetadataServiceType() {
        return "Postgres";
    }

    @Override
    public JsonNode databaseServiceRequest(DataSource dataSource, String stableServiceName) {
        ConnectionValues source = connectionValues(dataSource);
        if (isBlank(source.database())) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.SOURCE_CONNECTION_ERROR,
                    "Postgres requires a database for the OpenMetadata 1.12.10 connection schema");
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
}
