package org.apache.seatunnel.web.api.metadata.adapter;

import org.apache.seatunnel.web.api.metadata.OpenMetadataProperties;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Uses the verified CustomDatabase KingbaseSource shipped in the 1.12.10.x image. */
@Component
public class KingbaseMetadataConnectorAdapter extends CustomDatabaseMetadataConnectorAdapter {

    private final OpenMetadataProperties openMetadataProperties;

    /** Kept for the registry unit tests; production wiring uses the properties constructor. */
    public KingbaseMetadataConnectorAdapter() {
        this.openMetadataProperties = null;
    }

    @Autowired
    public KingbaseMetadataConnectorAdapter(OpenMetadataProperties openMetadataProperties) {
        this.openMetadataProperties = openMetadataProperties;
    }

    @Override
    public DbType dataSourceType() {
        return DbType.KINGBASE;
    }

    @Override
    protected String sourcePythonClass() {
        return "kingbase_connector.kingbase_source.KingbaseSource";
    }

    @Override
    protected ConnectionValues connectionValues(org.apache.seatunnel.web.dao.entity.DataSource dataSource) {
        ConnectionValues source = super.connectionValues(dataSource);
        if (openMetadataProperties == null
                || openMetadataProperties.getKingbaseTunnelHost() == null
                || openMetadataProperties.getKingbaseTunnelHost().isBlank()
                || openMetadataProperties.getKingbaseTunnelPort() <= 0) {
            return source;
        }
        return new ConnectionValues(
                openMetadataProperties.getKingbaseTunnelHost() + ":"
                        + openMetadataProperties.getKingbaseTunnelPort(),
                source.database(),
                source.username(),
                source.password());
    }
}
