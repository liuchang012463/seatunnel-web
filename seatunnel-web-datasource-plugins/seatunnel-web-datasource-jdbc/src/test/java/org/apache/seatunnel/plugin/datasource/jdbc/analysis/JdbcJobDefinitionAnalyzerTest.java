package org.apache.seatunnel.plugin.datasource.jdbc.analysis;

import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.analysis.DatasourceAnalysisContext;
import org.apache.seatunnel.plugin.datasource.api.analysis.DatasourceAnalysisRole;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.modal.JobDefinitionAnalysisResult;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcJobDefinitionAnalyzerTest {

    private final JdbcJobDefinitionAnalyzer analyzer = new JdbcJobDefinitionAnalyzer();

    @Test
    void analyzesJdbcSource() {
        DatasourceAnalysisContext context = context(
                DatasourceAnalysisRole.SOURCE,
                "table_path = catalog.public.orders");

        JobDefinitionAnalysisResult result = analyzer.analyze(context);

        assertTrue(analyzer.supports(context));
        assertEquals(DbType.JDBC.name(), result.getSourceType());
        assertEquals(1001L, result.getSourceDatasourceId());
        assertEquals("catalog.public.orders", result.getSourceTable());
    }

    @Test
    void analyzesJdbcSink() {
        JobDefinitionAnalysisResult result = analyzer.analyze(context(
                DatasourceAnalysisRole.SINK,
                "targetTableName = orders_archive"));

        assertEquals(DbType.JDBC.name(), result.getSinkType());
        assertEquals(1001L, result.getSinkDatasourceId());
        assertEquals("orders_archive", result.getSinkTable());
    }

    private DatasourceAnalysisContext context(
            DatasourceAnalysisRole role,
            String pluginConfig) {
        return DatasourceAnalysisContext.builder()
                .mode(JobDefinitionMode.GUIDE_SINGLE)
                .role(role)
                .dbType(DbType.JDBC)
                .datasourceId(1001L)
                .pluginConfig(ConfigFactory.parseString(pluginConfig))
                .build();
    }
}
