package org.apache.seatunnel.plugin.datasource.api.datasource;

import org.apache.seatunnel.web.spi.datasource.ConnectionParam;

/** Verifies that a datasource can be reached with the supplied connection parameters. */
public interface ConnectivityVerifier {

    boolean checkDataSourceConnectivity(ConnectionParam connectionParam);
}
