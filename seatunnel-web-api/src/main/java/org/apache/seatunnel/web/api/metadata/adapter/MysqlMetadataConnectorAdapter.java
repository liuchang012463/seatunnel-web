package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

/** Fixed OpenMetadata 1.12.10 Mysql connection schema adapter. */
@Component
public class MysqlMetadataConnectorAdapter extends AbstractDatabaseMetadataConnectorAdapter {

    @Override
    public DbType dataSourceType() {
        return DbType.MYSQL;
    }

    @Override
    public String openMetadataServiceType() {
        return "Mysql";
    }

    @Override
    public JsonNode databaseServiceRequest(DataSource dataSource, String stableServiceName) {
        ConnectionValues source = connectionValues(dataSource);
        ObjectNode root = baseServiceRequest(dataSource, stableServiceName);
        ObjectNode config = root.putObject("connection").putObject("config");
        config.put("type", "Mysql");
        config.put("scheme", "mysql+pymysql");
        config.put("username", source.username());
        config.set("authType", passwordAuth(source.password()));
        config.put("hostPort", source.hostPort());
        if (!isBlank(source.database())) {
            config.put("databaseName", source.database());
        }
        config.set("schemaFilterPattern", mysqlSchemaFilter());
        config.put("supportsMetadataExtraction", true);
        config.put("supportsProfiler", true);
        return root;
    }

    private ObjectNode mysqlSchemaFilter() {
        ObjectNode filter = filterPattern();
        filter.withArray("excludes").add("^information_schema$").add("^performance_schema$");
        return filter;
    }
}
