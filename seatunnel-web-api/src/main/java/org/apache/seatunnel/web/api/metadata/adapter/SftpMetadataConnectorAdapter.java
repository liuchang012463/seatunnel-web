package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.api.metadata.MetadataServiceCategory;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

/** Fixed OpenMetadata 1.12.10 SFTP drive service adapter. */
@Component
public class SftpMetadataConnectorAdapter extends AbstractNonDatabaseMetadataConnectorAdapter {

    @Override
    public DbType dataSourceType() {
        return DbType.SFTP;
    }

    @Override
    public String openMetadataServiceType() {
        return "Sftp";
    }

    @Override
    public MetadataServiceCategory serviceCategory() {
        return MetadataServiceCategory.DRIVE;
    }

    @Override
    public JsonNode serviceRequest(DataSource dataSource, String stableServiceName) {
        JsonNode source = rawConnection(dataSource);
        String host = connectionText(source, "host");
        String username = connectionText(source, "user");
        String password = connectionText(source, "password");
        if (isBlank(host) || isBlank(username) || isBlank(password)) {
            throw invalidConnectionFailure();
        }

        ObjectNode root = baseServiceRequest(dataSource, stableServiceName);
        ObjectNode config = root.putObject("connection").putObject("config");
        config.put("type", "Sftp");
        config.put("host", host);

        JsonNode portNode = source.get("port");
        if (portNode != null && !portNode.isNull()) {
            config.put("port", portNode.asInt());
        } else {
            config.put("port", 22);
        }

        ObjectNode authType = OBJECT_MAPPER.createObjectNode();
        authType.put("username", username);
        authType.put("password", decodePassword(password));
        config.set("authType", authType);

        String basePath = connectionText(source, "basePath");
        if (isBlank(basePath)) {
            basePath = "/";
        }
        config.withArray("rootDirectories").add(basePath);
        config.put("supportsMetadataExtraction", true);
        return root;
    }
}
