package org.apache.seatunnel.plugin.datasource.s3.param;

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
public abstract class ObjectStorageConnectionParam implements ConnectionParam {

    @FormField(label = "Endpoint", required = true, order = 1,
            placeholder = "https://s3.cn-north-1.amazonaws.com.cn")
    private String endpoint;

    @FormField(label = "Region", required = true, order = 2, defaultValue = "us-east-1")
    private String region = "us-east-1";

    @FormField(label = "Bucket", required = true, order = 3, placeholder = "seatunnel-data")
    private String bucket;

    @FormField(label = "根 Prefix", required = true, order = 4,
            defaultValue = "/", placeholder = "/incoming")
    private String basePath = "/";

    @FormField(label = "连接超时（毫秒）", required = true, type = FieldType.NUMBER,
            order = 20, defaultValue = "10000")
    private Integer connectTimeoutMs = 10000;

    @FormField(label = "请求超时（毫秒）", required = true, type = FieldType.NUMBER,
            order = 21, defaultValue = "30000")
    private Integer requestTimeoutMs = 30000;

    private DbType dbType;

    public abstract ObjectStorageCredentialMode credentialMode();

    public abstract String accessKey();

    public abstract String secretKey();

    public abstract boolean pathStyleAccess();

    @Override
    public String getUser() {
        return accessKey();
    }

    @Override
    public String getPassword() {
        return secretKey();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{endpoint='" + endpoint
                + "', region='" + region
                + "', bucket='" + bucket
                + "', basePath='" + basePath
                + "', credentialMode=" + credentialMode()
                + ", pathStyleAccess=" + pathStyleAccess()
                + ", dbType=" + dbType + "}";
    }
}
