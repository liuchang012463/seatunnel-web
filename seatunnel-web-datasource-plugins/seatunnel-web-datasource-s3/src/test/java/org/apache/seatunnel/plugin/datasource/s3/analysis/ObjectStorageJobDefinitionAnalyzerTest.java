package org.apache.seatunnel.plugin.datasource.s3.analysis;

import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.analysis.DatasourceAnalysisContext;
import org.apache.seatunnel.plugin.datasource.api.analysis.DatasourceAnalysisRole;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.modal.JobDefinitionAnalysisResult;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectStorageJobDefinitionAnalyzerTest {

    @Test
    void echoesS3SourcePath() {
        ObjectStorageJobDefinitionAnalyzer analyzer =
                new ObjectStorageJobDefinitionAnalyzer(DbType.S3);
        DatasourceAnalysisContext context =
                context(DatasourceAnalysisRole.SOURCE, DbType.S3, "path = /incoming/images");

        JobDefinitionAnalysisResult result = analyzer.analyze(context);

        assertTrue(analyzer.supports(context));
        assertEquals(DbType.S3.name(), result.getSourceType());
        assertEquals(1001L, result.getSourceDatasourceId());
        assertEquals("/incoming/images", result.getSourceTable());
    }

    @Test
    void echoesMinioSinkPath() {
        ObjectStorageJobDefinitionAnalyzer analyzer =
                new ObjectStorageJobDefinitionAnalyzer(DbType.MINIO);
        JobDefinitionAnalysisResult result =
                analyzer.analyze(
                        context(
                                DatasourceAnalysisRole.SINK,
                                DbType.MINIO,
                                "targetPath = /archive"));

        assertEquals(DbType.MINIO.name(), result.getSinkType());
        assertEquals(1001L, result.getSinkDatasourceId());
        assertEquals("/archive", result.getSinkTable());
    }

    private DatasourceAnalysisContext context(
            DatasourceAnalysisRole role, DbType dbType, String pluginConfig) {
        return DatasourceAnalysisContext.builder()
                .mode(JobDefinitionMode.GUIDE_SINGLE)
                .role(role)
                .dbType(dbType)
                .datasourceId(1001L)
                .pluginConfig(ConfigFactory.parseString(pluginConfig))
                .build();
    }
}
