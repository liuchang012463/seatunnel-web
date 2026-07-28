package org.apache.seatunnel.plugin.datasource.s3.param;

import lombok.Getter;
import lombok.Setter;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.form.FieldType;
import org.apache.seatunnel.web.spi.form.FormField;

@Getter
@Setter
public class MinioConnectionParam extends ObjectStorageConnectionParam {

    @FormField(label = "Access Key", required = true, type = FieldType.PASSWORD, order = 5)
    private String accessKey;

    @FormField(label = "Secret Key", required = true, type = FieldType.PASSWORD, order = 6)
    private String secretKey;

    public MinioConnectionParam() {
        setDbType(DbType.MINIO);
        setRegion("us-east-1");
    }

    @Override
    public ObjectStorageCredentialMode credentialMode() {
        return ObjectStorageCredentialMode.STATIC;
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
        return true;
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
