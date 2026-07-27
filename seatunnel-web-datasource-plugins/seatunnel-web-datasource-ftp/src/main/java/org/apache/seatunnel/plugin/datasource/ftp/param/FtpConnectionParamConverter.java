package org.apache.seatunnel.plugin.datasource.ftp.param;

import org.apache.seatunnel.web.spi.enums.DbType;

public class FtpConnectionParamConverter extends AbstractRemoteFileParamConverter<FtpConnectionParam> {
    public FtpConnectionParamConverter() {
        super(FtpConnectionParam.class, DbType.FTP);
    }
}
