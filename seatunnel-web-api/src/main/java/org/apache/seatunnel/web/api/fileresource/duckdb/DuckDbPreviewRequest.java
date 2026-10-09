package org.apache.seatunnel.web.api.fileresource.duckdb;

/** Read-only preview request for a selected DuckDB file resource. */
public record DuckDbPreviewRequest(
        String readMode,
        String schemaName,
        String tableName,
        String query,
        Integer limit) {
}
