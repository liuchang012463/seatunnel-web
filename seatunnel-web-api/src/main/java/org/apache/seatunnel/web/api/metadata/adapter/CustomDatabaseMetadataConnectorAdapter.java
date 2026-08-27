package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.dao.entity.DataSource;

/**
 * Adapter for the CustomDatabase extension point shipped with the deployed
 * OpenMetadata 1.12.10.x ingestion image.  The image loads the connector's
 * module-level get_connection function from sourcePythonClass, so this class
 * deliberately emits the documented CustomDatabase connection shape instead
 * of pretending the connector is a 1.13 built-in.
 */
abstract class CustomDatabaseMetadataConnectorAdapter extends AbstractDatabaseMetadataConnectorAdapter {

    protected abstract String sourcePythonClass();

    @Override
    public String openMetadataServiceType() {
        return "CustomDatabase";
    }

    @Override
    public JsonNode databaseServiceRequest(DataSource dataSource, String stableServiceName) {
        ConnectionValues source = connectionValues(dataSource);
        if (isBlank(source.password())) {
            throw invalidConnectionFailure();
        }
        JsonNode raw = rawConnection(dataSource);
        ObjectNode root = baseServiceRequest(dataSource, stableServiceName);
        ObjectNode config = root.putObject("connection").putObject("config");
        config.put("type", "CustomDatabase");
        config.put("sourcePythonClass", sourcePythonClass());

        ObjectNode options = config.putObject("connectionOptions");
        options.put("hostPort", source.hostPort());
        options.put("username", source.username());
        options.put("password", source.password());
        if (!isBlank(source.database())) {
            options.put("database", source.database());
        }
        String schema = firstNonBlank(
                connectionText(raw, "schemaName"), connectionText(raw, "schema"));
        if (!isBlank(schema)) {
            options.put("schema", schema);
            config.set("schemaFilterPattern", filterPattern(schema));
        }
        config.put("supportsMetadataExtraction", true);
        // CustomDatabaseConnection allows extension fields in 1.12.10.  The
        // flag lets the profiler workflow treat this source as profileable.
        config.put("supportsProfiler", true);
        return root;
    }

    private static String firstNonBlank(String first, String second) {
        return isBlank(first) ? second : first;
    }
}
