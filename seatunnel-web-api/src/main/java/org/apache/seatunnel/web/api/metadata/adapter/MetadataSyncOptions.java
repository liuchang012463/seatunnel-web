package org.apache.seatunnel.web.api.metadata.adapter;

/**
 * Operator decisions that shape the OpenMetadata objects of one data source. The
 * reconciler resolves them from the local binding so adapters never read local state.
 *
 * @param sampleDataEnabled whether the ingestion pipeline may read real payloads into
 *                          OpenMetadata; off unless the operator opted in
 * @param storageManifest   object-storage manifest (the same JSON a bucket's
 *                          {@code openmetadata.json} would hold) used to derive a
 *                          container data model; null when not configured
 */
public record MetadataSyncOptions(boolean sampleDataEnabled, String storageManifest) {

    public static final MetadataSyncOptions DEFAULT = new MetadataSyncOptions(false, null);

    public boolean hasStorageManifest() {
        return storageManifest != null && !storageManifest.isBlank();
    }
}
