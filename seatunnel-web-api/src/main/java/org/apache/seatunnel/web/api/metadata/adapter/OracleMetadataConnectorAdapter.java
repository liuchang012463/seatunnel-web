package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fixed OpenMetadata 1.12.10 Oracle connection schema adapter. */
@Component
public class OracleMetadataConnectorAdapter extends AbstractDatabaseMetadataConnectorAdapter {

    private static final Pattern SERVICE_URL = Pattern.compile(
            "^jdbc:oracle:thin:@//([^/:]+):(\\d+)/([^?]+)(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SID_URL = Pattern.compile(
            "^jdbc:oracle:thin:@([^/:]+):(\\d+):([^?]+)(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE);

    @Override
    public DbType dataSourceType() {
        return DbType.ORACLE;
    }

    @Override
    public String openMetadataServiceType() {
        return "Oracle";
    }

    @Override
    public JsonNode databaseServiceRequest(DataSource dataSource, String stableServiceName) {
        OracleValues source = oracleValues(dataSource);
        ObjectNode root = baseServiceRequest(dataSource, stableServiceName);
        ObjectNode config = root.putObject("connection").putObject("config");
        config.put("type", "Oracle");
        config.put("scheme", "oracle+cx_oracle");
        config.put("username", source.username());
        config.put("password", source.password());
        config.put("hostPort", source.hostPort());
        ObjectNode connectionType = config.putObject("oracleConnectionType");
        if (source.serviceName()) {
            connectionType.put("oracleServiceName", source.database());
        } else {
            connectionType.put("databaseSchema", source.database());
        }
        config.put("instantClientDirectory", "/instantclient");
        config.put("databaseName", source.database());
        ObjectNode schemaFilter = filterPattern();
        schemaFilter.withArray("excludes")
                .add("^sys$")
                .add("^ctxsys$")
                .add("^dbsnmp$")
                .add("^outln$");
        config.set("schemaFilterPattern", schemaFilter);
        config.put("supportsMetadataExtraction", true);
        config.put("supportsProfiler", true);
        return root;
    }

    private OracleValues oracleValues(DataSource dataSource) {
        try {
            JsonNode source = OBJECT_MAPPER.readTree(dataSource.getConnectionParams());
            String username = text(source, "username");
            if (isBlank(username)) {
                username = text(source, "user");
            }
            String password = text(source, "password");
            String host = text(source, "host");
            String port = text(source, "port");
            String database = text(source, "database");
            String connectType = text(source, "connectType");
            String url = text(source, "url");
            Matcher matcher = url == null ? null : SERVICE_URL.matcher(url);
            boolean serviceName = true;
            if (matcher != null && matcher.matches()) {
                host = firstNonBlank(host, matcher.group(1));
                port = firstNonBlank(port, matcher.group(2));
                database = firstNonBlank(database, matcher.group(3));
                serviceName = true;
            } else {
                matcher = url == null ? null : SID_URL.matcher(url);
                if (matcher != null && matcher.matches()) {
                    host = firstNonBlank(host, matcher.group(1));
                    port = firstNonBlank(port, matcher.group(2));
                    database = firstNonBlank(database, matcher.group(3));
                    serviceName = false;
                } else if (!isBlank(connectType)) {
                    serviceName = !"ORACLE_SID".equalsIgnoreCase(connectType);
                }
            }
            if (isBlank(host) || isBlank(port) || isBlank(database)
                    || isBlank(username) || isBlank(password)) {
                throw invalidConnection();
            }
            return new OracleValues(host + ":" + port, database, username, password, serviceName);
        } catch (MetadataIntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw invalidConnection();
        }
    }

    private static String text(JsonNode source, String field) {
        JsonNode value = source == null ? null : source.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String firstNonBlank(String first, String second) {
        return isBlank(first) ? second : first;
    }

    private static MetadataIntegrationException invalidConnection() {
        return new MetadataIntegrationException(
                MetadataErrorCode.SOURCE_CONNECTION_ERROR,
                "Oracle connection cannot be converted to the OpenMetadata 1.12.10 schema");
    }

    private record OracleValues(
            String hostPort, String database, String username, String password, boolean serviceName) {
    }
}
