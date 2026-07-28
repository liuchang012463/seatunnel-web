package org.apache.seatunnel.plugin.datasource.s3.param;

import lombok.Getter;
import lombok.Setter;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.form.FieldType;
import org.apache.seatunnel.web.spi.form.FormField;

@Getter
@Setter
public class S3ConnectionParam extends ObjectStorageConnectionParam {

    @FormField(label = "认证方式", required = true, type = FieldType.SELECT,
            order = 5, defaultValue = "STATIC")
    private ObjectStorageCredentialMode credentialMode = ObjectStorageCredentialMode.STATIC;

    @FormField(label = "Access Key", type = FieldType.PASSWORD, order = 6)
    private String accessKey;

    @FormField(label = "Secret Key", type = FieldType.PASSWORD, order = 7)
    private String secretKey;

    @FormField(label = "Path Style Access", required = true, type = FieldType.SWITCH,
            order = 8, defaultValue = "false")
    private Boolean pathStyleAccess = false;

    public S3ConnectionParam() {
        setDbType(DbType.S3);
    }

    @Override
    public ObjectStorageCredentialMode credentialMode() {
        return credentialMode;
    }

    @Override
    public String accessKey() {
        return accessKey;
    }

    @Override
    public String secretKey() {
        return secretKey;
    }

    @Override
    public boolean pathStyleAccess() {
        return Boolean.TRUE.equals(pathStyleAccess);
    }

    @Override
    public void setUser(String user) {
        this.accessKey = user;
    }

    @Override
    public void setPassword(String password) {
        this.secretKey = password;
    }
}
