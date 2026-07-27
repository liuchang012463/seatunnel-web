package org.apache.seatunnel.plugin.datasource.ftp.param;

import lombok.Getter;
import lombok.Setter;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.form.FieldType;
import org.apache.seatunnel.web.spi.form.FormField;

@Getter
@Setter
public class FtpConnectionParam extends RemoteFileConnectionParam {
    @FormField(label = "数据连接模式", required = true, type = FieldType.SELECT, order = 6, defaultValue = "PASSIVE_LOCAL")
    private FtpConnectionMode connectionMode = FtpConnectionMode.PASSIVE_LOCAL;

    @FormField(label = "校验数据连接远端地址", required = true, type = FieldType.SWITCH, order = 7, defaultValue = "true")
    private Boolean remoteVerificationEnabled = true;

    public FtpConnectionParam() {
        setDbType(DbType.FTP);
        setPort(21);
    }
}
