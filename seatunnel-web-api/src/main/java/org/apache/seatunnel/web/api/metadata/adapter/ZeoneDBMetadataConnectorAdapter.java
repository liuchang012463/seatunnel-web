package org.apache.seatunnel.web.api.metadata.adapter;

import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

/** Registers ZeoneDB with the PostgreSQL-compatible OpenMetadata 2.0.4 schema. */
@Component
public class ZeoneDBMetadataConnectorAdapter extends PostgresMetadataConnectorAdapter {

    @Override
    public DbType dataSourceType() {
        return DbType.ZEONEDB;
    }
}
