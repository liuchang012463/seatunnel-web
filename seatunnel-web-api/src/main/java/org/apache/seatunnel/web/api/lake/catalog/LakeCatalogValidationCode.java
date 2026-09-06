package org.apache.seatunnel.web.api.lake.catalog;

/** Stable, secret-free identities for bounded logical catalog observations. */
public final class LakeCatalogValidationCode {

    public static final String MATCH = "LAKE_CATALOG_MATCH";
    public static final String MISMATCH = "LAKE_CATALOG_MISMATCH";
    public static final String MISSING = "LAKE_CATALOG_MISSING";
    public static final String UNKNOWN = "LAKE_CATALOG_UNKNOWN";
    public static final String INPUT_INVALID = "LAKE_CATALOG_INPUT_INVALID";
    public static final String NAME_MISMATCH = "LAKE_CATALOG_NAME_MISMATCH";
    public static final String REQUIRED_PROPERTY_UNKNOWN =
            "LAKE_CATALOG_REQUIRED_PROPERTY_UNKNOWN";
    public static final String PROPERTY_MISMATCH = "LAKE_CATALOG_PROPERTY_MISMATCH";
    public static final String JDBC_URL_AMBIGUOUS = "LAKE_CATALOG_JDBC_URL_AMBIGUOUS";
    public static final String DATABASE_MISSING = "LAKE_CATALOG_DATABASE_MISSING";
    public static final String TABLE_MISSING = "LAKE_CATALOG_TABLE_MISSING";
    public static final String METADATA_UNAVAILABLE = "LAKE_CATALOG_METADATA_UNAVAILABLE";

    // Descriptive aliases retained for callers that classify validation
    // failures by the kind of bounded check that failed.
    public static final String CATALOG_NAME_MISMATCH = NAME_MISMATCH;
    public static final String SOURCE_UNAVAILABLE = METADATA_UNAVAILABLE;

    private LakeCatalogValidationCode() {
    }
}
