package org.apache.seatunnel.web.api.metadata;

/** OpenMetadata service entity category for metadata reconciliation. */
public enum MetadataServiceCategory {

    DATABASE("databaseService", "DatabaseMetadata"),
    MESSAGING("messagingService", "MessagingMetadata"),
    SEARCH("searchService", "SearchMetadata"),
    STORAGE("storageService", "StorageMetadata"),
    API("apiService", "ApiMetadata"),
    DRIVE("driveService", "DriveMetadata");

    private final String entityType;
    private final String metadataConfigType;

    MetadataServiceCategory(String entityType, String metadataConfigType) {
        this.entityType = entityType;
        this.metadataConfigType = metadataConfigType;
    }

    /** OpenMetadata entity reference type for pipelines and service links. */
    public String entityType() {
        return entityType;
    }

    /** Ingestion pipeline sourceConfig type for metadata extraction. */
    public String metadataConfigType() {
        return metadataConfigType;
    }
}
