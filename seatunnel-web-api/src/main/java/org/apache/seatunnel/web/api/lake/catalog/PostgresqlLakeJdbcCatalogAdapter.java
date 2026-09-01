package org.apache.seatunnel.web.api.lake.catalog;

import org.springframework.stereotype.Component;

/** PostgreSQL JDBC catalog adapter. */
@Component
public final class PostgresqlLakeJdbcCatalogAdapter implements LakeJdbcCatalogAdapter {

    @Override
    public LakeJdbcAdapterType type() {
        return LakeJdbcAdapterType.POSTGRESQL;
    }
}
