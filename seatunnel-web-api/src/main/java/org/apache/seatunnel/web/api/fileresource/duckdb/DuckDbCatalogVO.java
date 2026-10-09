package org.apache.seatunnel.web.api.fileresource.duckdb;

import java.util.List;

/** Read-only structure discovered in one DuckDB file resource. */
public record DuckDbCatalogVO(String databaseName, List<Schema> schemas) {

    public DuckDbCatalogVO {
        schemas = schemas == null ? List.of() : List.copyOf(schemas);
    }

    public record Schema(String name, List<Table> tables) {
        public Schema {
            tables = tables == null ? List.of() : List.copyOf(tables);
        }
    }

    public record Table(String name, String type, List<Column> columns) {
        public Table {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    public record Column(String name, String type, boolean nullable) {
    }
}
