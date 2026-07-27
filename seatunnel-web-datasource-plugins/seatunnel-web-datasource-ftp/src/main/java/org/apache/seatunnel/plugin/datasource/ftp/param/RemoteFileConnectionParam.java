package org.apache.seatunnel.plugin.datasource.ftp.param;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.form.FieldType;
import org.apache.seatunnel.web.spi.form.FormField;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class RemoteFileConnectionParam implements ConnectionParam {
    @FormField(label = "主机", required = true, order = 1, placeholder = "files.example.com")
    private String host;

    @FormField(label = "端口", required = true, type = FieldType.NUMBER, order = 2)
    private Integer port;

    @FormField(label = "用户名", required = true, order = 3)
    private String user;

    @FormField(label = "密码", required = true, type = FieldType.PASSWORD, order = 4)
    private String password;

    @FormField(label = "根目录", required = true, order = 5, defaultValue = "/", placeholder = "/data")
    private String basePath = "/";

    @FormField(label = "连接超时（毫秒）", required = true, type = FieldType.NUMBER, order = 20, defaultValue = "10000")
    private Integer connectTimeoutMs = 10000;

    @FormField(label = "数据超时（毫秒）", required = true, type = FieldType.NUMBER, order = 21, defaultValue = "30000")
    private Integer dataTimeoutMs = 30000;

    private DbType dbType;

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{host='" + host + "', port=" + port
                + ", user='" + user + "', basePath='" + basePath + "', dbType=" + dbType + "}";
    }
}
