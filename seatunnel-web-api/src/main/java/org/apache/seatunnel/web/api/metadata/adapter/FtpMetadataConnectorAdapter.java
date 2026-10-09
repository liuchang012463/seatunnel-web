package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.api.metadata.MetadataServiceCategory;
import org.apache.seatunnel.web.api.metadata.OmResourceType;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OpenMetadata 2.0.4 CustomDrive adapter for FTP sources.
 *
 * <p>OM has no built-in FTP drive type. The verified path matches Kingbase's
 * CustomDatabase pattern: deploy a CustomDrive service whose
 * {@code sourcePythonClass} points at the FTP connector shipped in the
 * ingestion extension bundle, and map connection fields into
 * {@code connectionOptions}.</p>
 */
@Component
public class FtpMetadataConnectorAdapter extends AbstractNonDatabaseMetadataConnectorAdapter {

    static final String SOURCE_PYTHON_CLASS = "ftp_connector.ftp_source.FtpSource";

    @Override
    public DbType dataSourceType() {
        return DbType.FTP;
    }

    @Override
    public String openMetadataServiceType() {
        return "CustomDrive";
    }

    @Override
    public MetadataServiceCategory serviceCategory() {
        return MetadataServiceCategory.DRIVE;
    }

    @Override
    public List<OmResourceType> resourceTypes() {
        return List.of(OmResourceType.DIRECTORY, OmResourceType.FILE);
    }

    @Override
    public boolean supportsSampleData() {
        return true;
    }

    @Override
    public JsonNode serviceRequest(DataSource dataSource, String stableServiceName) {
        return serviceRequest(dataSource, stableServiceName, MetadataSyncOptions.DEFAULT);
    }

    @Override
    public JsonNode serviceRequest(
            DataSource dataSource, String stableServiceName, MetadataSyncOptions options) {
        boolean sampleDataEnabled = options != null && options.sampleDataEnabled();
        JsonNode source = rawConnection(dataSource);
        String host = connectionText(source, "host");
        String username = connectionText(source, "user");
        String password = connectionText(source, "password");
        if (isBlank(host) || isBlank(username) || isBlank(password)) {
            throw invalidConnectionFailure();
        }

        ObjectNode root = baseServiceRequest(dataSource, stableServiceName);
        ObjectNode config = root.putObject("connection").putObject("config");
        config.put("type", "CustomDrive");
        config.put("sourcePythonClass", SOURCE_PYTHON_CLASS);

        ObjectNode connectionOptions = config.putObject("connectionOptions");
        connectionOptions.put("host", host);

        JsonNode portNode = source.get("port");
        if (portNode != null && !portNode.isNull()) {
            connectionOptions.put("port", String.valueOf(portNode.asInt()));
        } else {
            connectionOptions.put("port", "21");
        }

        connectionOptions.put("username", username);
        connectionOptions.put("password", decodePassword(password));

        String basePath = connectionText(source, "basePath");
        if (isBlank(basePath)) {
            basePath = "/";
        }
        connectionOptions.put("rootDirectories", basePath);

        String connectionMode = connectionText(source, "connectionMode");
        if (!isBlank(connectionMode)) {
            connectionOptions.put("connectionMode", connectionMode);
        } else {
            connectionOptions.put("connectionMode", "PASSIVE_LOCAL");
        }

        JsonNode remoteVerification = source.get("remoteVerificationEnabled");
        if (remoteVerification != null && !remoteVerification.isNull()) {
            connectionOptions.put(
                    "remoteVerificationEnabled", String.valueOf(remoteVerification.asBoolean()));
        }

        // Off by default: collecting file rows downloads file content into OpenMetadata.
        connectionOptions.put("extractSampleData", String.valueOf(sampleDataEnabled));
        config.put("supportsMetadataExtraction", true);
        return root;
    }
}
