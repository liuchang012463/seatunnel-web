package org.apache.seatunnel.plugin.datasource.api.datasource;

import org.apache.seatunnel.web.spi.datasource.ConnectionParam;

/** Converts persisted JSON into a datasource-specific connection model. */
public interface ConnectionParamConverter {

    ConnectionParam createConnectionParams(String connectionJson);

    void checkDatasourceParam(ConnectionParam connectionParam);
}
