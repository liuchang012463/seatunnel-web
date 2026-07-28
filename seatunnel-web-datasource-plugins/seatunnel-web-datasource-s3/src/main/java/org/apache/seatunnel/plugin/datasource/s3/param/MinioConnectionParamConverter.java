package org.apache.seatunnel.plugin.datasource.s3.param;

import org.apache.seatunnel.web.spi.enums.DbType;

public class MinioConnectionParamConverter extends AbstractObjectStorageParamConverter<MinioConnectionParam> {
    public MinioConnectionParamConverter() {
        super(MinioConnectionParam.class, DbType.MINIO);
    }

    @Override
    protected void validateCredentials(MinioConnectionParam param) {
        requireStaticCredentials(param);
    }
}
