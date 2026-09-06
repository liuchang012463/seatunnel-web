package org.apache.seatunnel.web.api.lake.catalog;

import org.springframework.stereotype.Component;

/** MySQL JDBC catalog adapter. */
@Component
public final class MysqlLakeJdbcCatalogAdapter implements LakeJdbcCatalogAdapter {

    @Override
    public LakeJdbcAdapterType type() {
        return LakeJdbcAdapterType.MYSQL;
    }
}
