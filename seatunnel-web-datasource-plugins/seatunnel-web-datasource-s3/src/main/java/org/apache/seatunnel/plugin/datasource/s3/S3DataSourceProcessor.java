package org.apache.seatunnel.plugin.datasource.s3;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.s3.client.ObjectStorageClient;
import org.apache.seatunnel.plugin.datasource.s3.client.S3ObjectStorageClient;
import org.apache.seatunnel.plugin.datasource.s3.param.S3ConnectionParamConverter;
import org.apache.seatunnel.web.spi.enums.DbType;

@AutoService(DataSourceProcessor.class)
public class S3DataSourceProcessor extends AbstractObjectStorageDataSourceProcessor {
    private final S3ConnectionParamConverter converter = new S3ConnectionParamConverter();
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
        return DbType.S3;
    }

    @Override
    public DataSourceProcessor create() {
        return new S3DataSourceProcessor();
    }
}
