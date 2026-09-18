package org.apache.seatunnel.web.core.verify.modal;

import lombok.Builder;
import lombok.Value;

/**
 * Optional database/schema/table scope supplied by a task editor.
 *
 * <p>An empty scope deliberately means connection and metadata access only.
 * This is important for sink verification because a target database may not
 * contain a table yet.</p>
 */
@Value
@Builder
public class DatasourceVerifyScope {

    String database;

    String schema;

    String table;

    public boolean hasTable() {
        return table != null && !table.isBlank();
    }
}
