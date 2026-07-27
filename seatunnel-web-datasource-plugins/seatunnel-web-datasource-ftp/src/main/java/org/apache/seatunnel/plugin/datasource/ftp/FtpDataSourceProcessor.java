package org.apache.seatunnel.plugin.datasource.ftp;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.ftp.client.FtpRemoteFileClient;
import org.apache.seatunnel.plugin.datasource.ftp.client.RemoteFileClient;
import org.apache.seatunnel.plugin.datasource.ftp.param.FtpConnectionParamConverter;
import org.apache.seatunnel.web.spi.enums.DbType;

@AutoService(DataSourceProcessor.class)
public class FtpDataSourceProcessor extends AbstractRemoteFileDataSourceProcessor {
    private final FtpConnectionParamConverter converter = new FtpConnectionParamConverter();
    private final FtpRemoteFileClient client = new FtpRemoteFileClient();
    @Override protected String connectorName() { return "FtpFile"; }
    @Override protected ConnectionParamConverter converter() { return converter; }
    @Override protected RemoteFileClient client() { return client; }
    @Override public DbType getDbType() { return DbType.FTP; }
    @Override public DataSourceProcessor create() { return new FtpDataSourceProcessor(); }
}
