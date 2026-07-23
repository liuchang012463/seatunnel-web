package org.apache.seatunnel.plugin.datasource.sftp.param;

import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SftpParamConverterTest {

    private final SftpParamConverter converter = new SftpParamConverter();

    @Test
    void parsesSeaTunnelCompatibleConnectionFields() {
        SftpConnectionParam param = (SftpConnectionParam) converter.createConnectionParams(
                "{\"host\":\"sftp.example.com\",\"port\":22,"
                        + "\"user\":\"seatunnel\",\"password\":\"secret\"}");

        assertEquals("sftp.example.com", param.getHost());
        assertEquals("22", param.getPort());
        assertEquals("seatunnel", param.getUser());
        assertEquals("secret", param.getPassword());
        assertEquals(DbType.SFTP, param.getDbType());
    }

    @Test
    void readsLegacyUsernameField() {
        SftpConnectionParam param = (SftpConnectionParam) converter.createConnectionParams(
                "{\"host\":\"localhost\",\"username\":\"legacy\",\"password\":\"secret\"}");

        assertEquals("legacy", param.getUser());
    }

    @Test
    void rejectsMissingPasswordAndInvalidPort() {
        SftpConnectionParam missingPassword = (SftpConnectionParam) converter.createConnectionParams(
                "{\"host\":\"localhost\",\"port\":22,\"user\":\"seatunnel\"}");
        assertThrows(IllegalArgumentException.class,
                () -> converter.checkDatasourceParam(missingPassword));

        SftpConnectionParam invalidPort = (SftpConnectionParam) converter.createConnectionParams(
                "{\"host\":\"localhost\",\"port\":70000,"
                        + "\"user\":\"seatunnel\",\"password\":\"secret\"}");
        assertThrows(IllegalArgumentException.class,
                () -> converter.checkDatasourceParam(invalidPort));
    }
}
