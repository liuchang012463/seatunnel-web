package org.apache.seatunnel.web.api.metadata.client;

import java.util.List;

/**
 * A small projection of OpenMetadata 2.0.4 paging responses.
 *
 * <p>The {@code after} token is intentionally kept opaque. Callers must pass it
 * back to the same collection endpoint and must not manufacture a cursor from a
 * page number. {@code scannedCount} preserves the server page size when the
 * projected data is filtered locally.</p>
 */
public record OpenMetadataPage<T>(List<T> data, long total, String after, int scannedCount) {

    public OpenMetadataPage(List<T> data, long total, String after) {
        this(data, total, after, data == null ? 0 : data.size());
    }

    public OpenMetadataPage {
        data = data == null ? List.of() : List.copyOf(data);
        total = Math.max(0L, total);
        after = after == null || after.isBlank() ? null : after;
        scannedCount = Math.max(data.size(), scannedCount);
    }
}
