package org.apache.seatunnel.plugin.datasource.ftp.param;

import org.apache.seatunnel.web.spi.enums.DbType;

public class SftpConnectionParamConverter extends AbstractRemoteFileParamConverter<SftpConnectionParam> {
    public SftpConnectionParamConverter() {
        super(SftpConnectionParam.class, DbType.SFTP);
    }
}
