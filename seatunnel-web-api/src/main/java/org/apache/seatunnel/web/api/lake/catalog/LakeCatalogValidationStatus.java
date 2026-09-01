package org.apache.seatunnel.web.api.lake.catalog;

/**
 * Result of a bounded logical catalog observation.
 *
 * <p>UNKNOWN is deliberately distinct from MISMATCH: a failed or ambiguous
 * read must not be treated as permission to mutate the external catalog.</p>
 */
public enum LakeCatalogValidationStatus {
    MATCH,
    MISMATCH,
    UNKNOWN,
    MISSING
}
