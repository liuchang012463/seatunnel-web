package org.apache.seatunnel.web.api.fileresource.duckdb;

import java.util.List;
import java.util.Map;

/** Bounded query result returned by the DuckDB source preview. */
public record DuckDbPreviewVO(List<Column> columns, List<Map<String, Object>> data, long total) {

    public DuckDbPreviewVO {
        columns = columns == null ? List.of() : List.copyOf(columns);
        data = data == null ? List.of() : List.copyOf(data);
    }

    public record Column(String name, String key, String type, boolean nullable) {
    }
}
