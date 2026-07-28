package org.apache.seatunnel.plugin.datasource.ftp.param;

import org.apache.seatunnel.web.spi.enums.DbType;

public class SftpConnectionParam extends RemoteFileConnectionParam {
    public SftpConnectionParam() {
        setDbType(DbType.SFTP);
        setPort(22);
    }
}
