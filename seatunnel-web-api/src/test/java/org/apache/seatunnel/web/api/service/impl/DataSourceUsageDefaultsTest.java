package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.spi.bean.dto.DataSourceDTO;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataSourceUsageDefaultsTest {

    private final DataSourceServiceImpl service = new DataSourceServiceImpl();

    @Test
    void defaultsLegacyCreateRequestsToTheConnectorCapabilities() {
        DataSourceDTO http = new DataSourceDTO();
        http.setDbType(DbType.HTTP);

        service.applyCreateUsageDefaults(http);

        assertEquals(true, http.getSourceEnabled());
        assertEquals(false, http.getSinkEnabled());

        DataSourceDTO mysql = new DataSourceDTO();
        mysql.setDbType(DbType.MYSQL);

        service.applyCreateUsageDefaults(mysql);

        assertEquals(true, mysql.getSourceEnabled());
        assertEquals(true, mysql.getSinkEnabled());
    }

    @Test
    void preservesAnyPurposeFlagsAlreadyProvidedByTheClient() {
        DataSourceDTO http = new DataSourceDTO();
        http.setDbType(DbType.HTTP);
        http.setSourceEnabled(false);

        service.applyCreateUsageDefaults(http);

        assertEquals(false, http.getSourceEnabled());
        assertEquals(false, http.getSinkEnabled());
    }
}
