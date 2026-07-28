package org.apache.seatunnel.plugin.datasource.s3.param;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.spi.enums.DbType;

public class S3ConnectionParamConverter extends AbstractObjectStorageParamConverter<S3ConnectionParam> {
    public S3ConnectionParamConverter() {
        super(S3ConnectionParam.class, DbType.S3);
    }

    @Override
    protected void validateCredentials(S3ConnectionParam param) {
        if (param.getCredentialMode() == null) {
            throw new IllegalArgumentException("S3 credentialMode cannot be empty");
        }
        if (param.getCredentialMode() == ObjectStorageCredentialMode.STATIC) {
            requireStaticCredentials(param);
            return;
        }
        if (StringUtils.isNotBlank(param.getAccessKey()) || StringUtils.isNotBlank(param.getSecretKey())) {
            throw new IllegalArgumentException("S3 INSTANCE_PROFILE mode must not persist accessKey or secretKey");
        }
    }
}
