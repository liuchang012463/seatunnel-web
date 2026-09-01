package org.apache.seatunnel.web.api.lake.query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded result returned by the executor; it deliberately has no SQL field. */
public record LakeReadOnlyQueryResultVO(
        List<String> columns,
        List<Map<String, Object>> rows,
        long rowCount,
        long byteCount,
        boolean truncated,
        long elapsedMillis,
        boolean explain) {

    public LakeReadOnlyQueryResultVO {
        columns = columns == null ? List.of() : List.copyOf(columns);
        if (rows == null || rows.isEmpty()) {
            rows = List.of();
        } else {
            rows = rows.stream().map(LakeReadOnlyQueryResultVO::copyRow).toList();
        }
    }

    private static Map<String, Object> copyRow(Map<String, Object> row) {
        return row == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(row));
    }
}
