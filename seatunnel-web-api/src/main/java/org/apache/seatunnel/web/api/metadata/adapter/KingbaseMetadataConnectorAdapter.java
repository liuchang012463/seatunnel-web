package org.apache.seatunnel.web.api.metadata.adapter;

import org.apache.seatunnel.web.api.metadata.OpenMetadataConfigResolver;
import org.apache.seatunnel.web.api.metadata.OpenMetadataProperties;
import org.apache.seatunnel.web.api.metadata.OpenMetadataRuntimeConfig;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Uses the verified CustomDatabase KingbaseSource shipped in the 1.12.10.x image. */
@Component
public class KingbaseMetadataConnectorAdapter extends CustomDatabaseMetadataConnectorAdapter {

    private final OpenMetadataConfigResolver configResolver;

    /** Kept for the registry unit tests; production wiring uses the resolver constructor. */
    public KingbaseMetadataConnectorAdapter() {
        this.configResolver = null;
    }

    @Autowired
    public KingbaseMetadataConnectorAdapter(OpenMetadataConfigResolver configResolver) {
        this.configResolver = configResolver;
    }

    /** Backward-compatible constructor for older unit tests. */
    public KingbaseMetadataConnectorAdapter(OpenMetadataProperties openMetadataProperties) {
        this.configResolver = OpenMetadataConfigResolver.fixed(openMetadataProperties);
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
        OpenMetadataRuntimeConfig runtime =
                configResolver == null ? null : configResolver.resolve();
        if (runtime == null
                || runtime.getKingbaseTunnelHost() == null
                || runtime.getKingbaseTunnelHost().isBlank()
                || runtime.getKingbaseTunnelPort() <= 0) {
            return source;
        }
        return new ConnectionValues(
                runtime.getKingbaseTunnelHost() + ":" + runtime.getKingbaseTunnelPort(),
                source.database(),
                source.username(),
                source.password());
    }
}
