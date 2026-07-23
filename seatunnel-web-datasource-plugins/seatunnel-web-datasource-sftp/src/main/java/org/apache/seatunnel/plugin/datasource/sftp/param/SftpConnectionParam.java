package org.apache.seatunnel.plugin.datasource.sftp.param;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.form.FieldType;
import org.apache.seatunnel.web.spi.form.FormField;

@Data
@EqualsAndHashCode(callSuper = true)
public class SftpConnectionParam extends BaseConnectionParam {

    @FormField(label = "校验主机密钥", required = false, order = 6, type = FieldType.SWITCH, defaultValue = "false")
    private Boolean strictHostKeyChecking;

    @FormField(
            label = "Known Hosts 文件路径",
            required = false,
            order = 7,
            placeholder = "/path/to/known_hosts")
    private String knownHostsPath;
}
