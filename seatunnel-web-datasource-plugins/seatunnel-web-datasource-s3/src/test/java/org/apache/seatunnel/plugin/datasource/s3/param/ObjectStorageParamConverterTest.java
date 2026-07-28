package org.apache.seatunnel.plugin.datasource.s3.param;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObjectStorageParamConverterTest {

    @Test
    void validatesAndNormalizesStaticS3() {
        S3ConnectionParam param = new S3ConnectionParamConverter().createConnectionParams("""
                {
                  "endpoint": "https://s3.cn-north-1.amazonaws.com.cn/",
                  "region": "cn-north-1",
                  "bucket": "archive",
                  "basePath": "/incoming/",
                  "credentialMode": "STATIC",
                  "accessKey": "access",
                  "secretKey": "secret"
                }
                """);

        new S3ConnectionParamConverter().checkDatasourceParam(param);
        assertEquals("https://s3.cn-north-1.amazonaws.com.cn", param.getEndpoint());
        assertEquals("/incoming", param.getBasePath());
        assertFalse(param.toString().contains("access"));
        assertFalse(param.toString().contains("secret"));
    }

    @Test
    void supportsIamOnlyWithoutPersistedKeys() {
        S3ConnectionParam param = new S3ConnectionParam();
        param.setEndpoint("https://s3.us-east-1.amazonaws.com");
        param.setRegion("us-east-1");
        param.setBucket("archive");
        param.setCredentialMode(ObjectStorageCredentialMode.INSTANCE_PROFILE);
        new S3ConnectionParamConverter().checkDatasourceParam(param);

        param.setAccessKey("must-not-be-stored");
        assertThrows(IllegalArgumentException.class,
                () -> new S3ConnectionParamConverter().checkDatasourceParam(param));
    }

    @Test
    void minioRequiresStaticCredentials() {
        MinioConnectionParam param = new MinioConnectionParam();
        param.setEndpoint("http://minio.local:9000");
        param.setBucket("archive");
        assertThrows(IllegalArgumentException.class,
                () -> new MinioConnectionParamConverter().checkDatasourceParam(param));

        param.setAccessKey("minio");
        param.setSecretKey("minio-secret");
        new MinioConnectionParamConverter().checkDatasourceParam(param);
    }

    @Test
    void rejectsInvalidEndpointBucketAndPrefix() {
        S3ConnectionParamConverter converter = new S3ConnectionParamConverter();
        assertThrows(IllegalArgumentException.class,
                () -> converter.createConnectionParams("{\"basePath\":\"/safe/../escape\"}"));

        S3ConnectionParam param = new S3ConnectionParam();
        param.setEndpoint("file:///tmp");
        param.setBucket("s3a://archive");
        param.setAccessKey("access");
        param.setSecretKey("secret");
        assertThrows(IllegalArgumentException.class, () -> converter.checkDatasourceParam(param));
    }
}
