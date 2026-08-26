package org.apache.seatunnel.web.api.metadata.client;

import java.util.List;

/**
 * A small projection of OpenMetadata 1.12.10 paging responses.
 *
 * <p>The {@code after} token is intentionally kept opaque.  Callers must
 * pass it back to the same collection endpoint and must not manufacture a
 * cursor from a page number.</p>
 */
public record OpenMetadataPage<T>(List<T> data, long total, String after) {

    public OpenMetadataPage {
        data = data == null ? List.of() : List.copyOf(data);
        total = Math.max(0L, total);
        after = after == null || after.isBlank() ? null : after;
    }
}
