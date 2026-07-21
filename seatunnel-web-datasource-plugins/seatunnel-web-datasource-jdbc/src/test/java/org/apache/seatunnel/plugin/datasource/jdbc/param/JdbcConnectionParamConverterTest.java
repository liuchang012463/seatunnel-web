package org.apache.seatunnel.plugin.datasource.jdbc.param;

import org.apache.seatunnel.plugin.datasource.api.form.ReflectionFormGenerator;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.form.FormFieldConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcConnectionParamConverterTest {

    private final JdbcConnectionParamConverter converter =
            new JdbcConnectionParamConverter();

    @Test
    void parsesGenericJdbcConnection() {
        BaseConnectionParam param = converter.createConnectionParams(
                "{\"url\":\"jdbc:vendor://db:1234/catalog\","
                        + "\"driver\":\"com.vendor.Driver\","
                        + "\"driverLocation\":\"vendor.jar\","
                        + "\"user\":\"test\",\"password\":\"secret\"}");

        assertEquals(DbType.JDBC, param.getDbType());
        assertEquals("jdbc:vendor://db:1234/catalog", param.getUrl());
        converter.checkDatasourceParam(param);
    }

    @Test
    void rejectsMissingRequiredConnectionValues() {
        BaseConnectionParam param = converter.createConnectionParams("{}");

        assertThrows(
                IllegalArgumentException.class,
                () -> converter.checkDatasourceParam(param));
    }

    @Test
    void formUsesJdbcUrlInsteadOfHostAndPort() {
        List<String> keys = ReflectionFormGenerator.generate(JdbcConnectionParam.class)
                .stream()
                .map(FormFieldConfig::getKey)
                .collect(Collectors.toList());

        assertTrue(keys.contains("url"));
        assertTrue(keys.contains("driver"));
        assertTrue(keys.contains("driverLocation"));
        assertFalse(keys.contains("host"));
        assertFalse(keys.contains("port"));
    }
}
