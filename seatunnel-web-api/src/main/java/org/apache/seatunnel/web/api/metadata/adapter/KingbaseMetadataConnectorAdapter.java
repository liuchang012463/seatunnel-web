package org.apache.seatunnel.web.api.metadata.adapter;

import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

/** Uses the verified CustomDatabase KingbaseSource shipped in the 1.12.10.x image. */
@Component
public class KingbaseMetadataConnectorAdapter extends CustomDatabaseMetadataConnectorAdapter {

    @Override
    public DbType dataSourceType() {
        return DbType.KINGBASE;
    }

    @Override
    protected String sourcePythonClass() {
        return "kingbase_connector.kingbase_source.KingbaseSource";
    }
}
