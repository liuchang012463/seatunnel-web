package org.apache.seatunnel.plugin.datasource.ftp;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.ftp.client.RemoteFileClient;
import org.apache.seatunnel.plugin.datasource.ftp.client.SftpRemoteFileClient;
import org.apache.seatunnel.plugin.datasource.ftp.param.SftpConnectionParamConverter;
import org.apache.seatunnel.web.spi.enums.DbType;

@AutoService(DataSourceProcessor.class)
public class SftpDataSourceProcessor extends AbstractRemoteFileDataSourceProcessor {
    private final SftpConnectionParamConverter converter = new SftpConnectionParamConverter();
    private final SftpRemoteFileClient client = new SftpRemoteFileClient();
    @Override protected String connectorName() { return "SftpFile"; }
    @Override protected ConnectionParamConverter converter() { return converter; }
    @Override protected RemoteFileClient client() { return client; }
    @Override public DbType getDbType() { return DbType.SFTP; }
    @Override public DataSourceProcessor create() { return new SftpDataSourceProcessor(); }
}
