package org.apache.seatunnel.plugin.datasource.s3;

import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.form.FieldType;
import org.apache.seatunnel.web.spi.form.FormFieldConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3SpiTest {

    @Test
    void discoversBothProcessorsAndSharedBuilder() {
        assertTrue(ServiceLoader.load(DataSourceProcessor.class).stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(item -> item.getDbType() == DbType.S3));
        assertTrue(ServiceLoader.load(DataSourceProcessor.class).stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(item -> item.getDbType() == DbType.MINIO));
        assertTrue(ServiceLoader.load(DataSourceHoconBuilder.class).stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(item -> "S3File".equals(item.pluginName())));
        assertNotNull(new S3DataSourceProcessor().getJobDefinitionAnalyzer());
        assertNotNull(new MinioDataSourceProcessor().getJobDefinitionAnalyzer());
    }

    @Test
    void generatesObjectStorageFormsWithPasswordCredentials() {
        List<FormFieldConfig> s3Fields = new S3DataSourceProcessor().generateFormFields();
        List<FormFieldConfig> minioFields = new MinioDataSourceProcessor().generateFormFields();

        assertTrue(hasField(s3Fields, "endpoint", FieldType.INPUT));
        assertTrue(hasField(s3Fields, "region", FieldType.INPUT));
        assertTrue(hasField(s3Fields, "bucket", FieldType.INPUT));
        assertTrue(hasField(s3Fields, "basePath", FieldType.INPUT));
        assertTrue(hasField(s3Fields, "credentialMode", FieldType.SELECT));
        assertTrue(hasField(s3Fields, "accessKey", FieldType.PASSWORD));
        assertTrue(hasField(s3Fields, "secretKey", FieldType.PASSWORD));
        assertTrue(hasField(minioFields, "accessKey", FieldType.PASSWORD));
        assertTrue(hasField(minioFields, "secretKey", FieldType.PASSWORD));
        assertEquals(
                "us-east-1",
                minioFields.stream()
                        .filter(field -> "region".equals(field.getKey()))
                        .findFirst()
                        .orElseThrow()
                        .getDefaultValue());
    }

    private boolean hasField(List<FormFieldConfig> fields, String key, FieldType type) {
        return fields.stream()
                .anyMatch(field -> key.equals(field.getKey()) && type == field.getType());
    }
}
