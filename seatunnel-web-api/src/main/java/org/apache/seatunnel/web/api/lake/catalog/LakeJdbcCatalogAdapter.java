package org.apache.seatunnel.web.api.lake.catalog;

import org.apache.seatunnel.web.api.lake.DorisIdentifier;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;

/** Adapter metadata used before a JDBC catalog is planned or executed. */
public interface LakeJdbcCatalogAdapter {

    LakeJdbcAdapterType type();

    /** All three P0 adapters can express the three metadata scopes. */
    default boolean supportsScope(LakeCatalogScope scope) {
        return scope != null;
    }

    default String normalizeDatabase(String databaseName) {
        // JDBC metadata names are passed to Doris include lists verbatim.  In
        // particular Oracle and PostgreSQL commonly use meaningful case, so
        // this must validate rather than apply catalog-name normalization.
        return DorisIdentifier.validate(databaseName).trim();
    }

    default String normalizeTable(String tableName) {
        return DorisIdentifier.validate(tableName).trim();
    }
}
