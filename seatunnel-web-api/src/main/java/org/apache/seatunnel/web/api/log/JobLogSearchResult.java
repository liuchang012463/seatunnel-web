package org.apache.seatunnel.web.api.log;

import java.util.List;

public record JobLogSearchResult(
        String keyword,
        String level,
        String source,
        String category,
        long total,
        int page,
        int pageSize,
        boolean hasMore,
        List<JobLogEntry> entries
) {
}
