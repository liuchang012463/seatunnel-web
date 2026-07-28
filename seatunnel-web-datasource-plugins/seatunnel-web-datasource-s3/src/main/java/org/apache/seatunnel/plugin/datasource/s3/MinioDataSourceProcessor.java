package org.apache.seatunnel.plugin.datasource.s3;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.s3.client.ObjectStorageClient;
import org.apache.seatunnel.plugin.datasource.s3.client.S3ObjectStorageClient;
import org.apache.seatunnel.plugin.datasource.s3.param.MinioConnectionParamConverter;
import org.apache.seatunnel.web.spi.enums.DbType;

@AutoService(DataSourceProcessor.class)
public class MinioDataSourceProcessor extends AbstractObjectStorageDataSourceProcessor {
    private final MinioConnectionParamConverter converter = new MinioConnectionParamConverter();
    private final S3ObjectStorageClient client = new S3ObjectStorageClient();

    @Override
    protected ConnectionParamConverter converter() {
        return converter;
    }

    @Override
    protected ObjectStorageClient client() {
        return client;
    }

    @Override
    public DbType getDbType() {
        return DbType.MINIO;
    }

    @Override
    public DataSourceProcessor create() {
        return new MinioDataSourceProcessor();
    }
}
