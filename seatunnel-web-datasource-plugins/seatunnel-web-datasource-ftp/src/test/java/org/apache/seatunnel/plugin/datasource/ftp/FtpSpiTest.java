package org.apache.seatunnel.plugin.datasource.ftp;

import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FtpSpiTest {
    @Test void discoversBothProcessorsAndBuilders() {
        assertTrue(ServiceLoader.load(DataSourceProcessor.class).stream()
                .map(ServiceLoader.Provider::get).anyMatch(item -> item.getDbType() == DbType.FTP));
        assertTrue(ServiceLoader.load(DataSourceProcessor.class).stream()
                .map(ServiceLoader.Provider::get).anyMatch(item -> item.getDbType() == DbType.SFTP));
        assertTrue(ServiceLoader.load(DataSourceHoconBuilder.class).stream()
                .map(ServiceLoader.Provider::get).anyMatch(item -> "FtpFile".equals(item.pluginName())));
        assertTrue(ServiceLoader.load(DataSourceHoconBuilder.class).stream()
                .map(ServiceLoader.Provider::get).anyMatch(item -> "SftpFile".equals(item.pluginName())));
    }
}
