package org.apache.seatunnel.web.api.lake.catalog;

import org.springframework.stereotype.Component;

/** Oracle JDBC catalog adapter. */
@Component
public final class OracleLakeJdbcCatalogAdapter implements LakeJdbcCatalogAdapter {

    @Override
    public LakeJdbcAdapterType type() {
        return LakeJdbcAdapterType.ORACLE;
    }
}
