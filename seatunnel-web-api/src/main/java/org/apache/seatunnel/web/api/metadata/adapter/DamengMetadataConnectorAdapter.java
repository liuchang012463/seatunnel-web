package org.apache.seatunnel.web.api.metadata.adapter;

import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

/** Uses the verified CustomDatabase DamengSource shipped in the 1.12.10.x image. */
@Component
public class DamengMetadataConnectorAdapter extends CustomDatabaseMetadataConnectorAdapter {

    @Override
    public DbType dataSourceType() {
        return DbType.DAMENG;
    }

    @Override
    protected String sourcePythonClass() {
        return "dameng_connector.dameng_source.DamengSource";
    }
}
