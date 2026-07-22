package org.apache.seatunnel.plugin.datasource.api.jdbc;

import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;

/**
 * Converter for JDBC connection parameters.
 */
public interface JdbcParamConverter extends ConnectionParamConverter {

    /**
     * Create connection parameters from JSON string.
     *
     * @param connectionJson JSON string containing connection details
     * @return parsed connection parameters
     */
    BaseConnectionParam createConnectionParams(String connectionJson);

    /**
     * check datasource param is valid.
     * @throws IllegalArgumentException if invalid
     */
    void checkDatasourceParam(BaseConnectionParam baseConnectionParam);

    @Override
    default void checkDatasourceParam(ConnectionParam connectionParam) {
        if (!(connectionParam instanceof BaseConnectionParam)) {
            throw new IllegalArgumentException("Expected JDBC connection parameters");
        }
        checkDatasourceParam((BaseConnectionParam) connectionParam);
    }
}
