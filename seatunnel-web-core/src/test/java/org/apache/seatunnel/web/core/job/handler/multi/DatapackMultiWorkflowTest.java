package org.apache.seatunnel.web.core.job.handler.multi;

import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.builder.EnvConfigBuilder;
import org.apache.seatunnel.web.core.builder.HoconConfigBuilder;
import org.apache.seatunnel.web.core.builder.NodeConfigBuilderRegistry;
import org.apache.seatunnel.web.core.builder.sink.SinkNodeConfigBuilder;
import org.apache.seatunnel.web.core.builder.source.SourceNodeConfigBuilder;
import org.apache.seatunnel.web.core.utils.DagUtil;
import org.apache.seatunnel.web.spi.bean.dto.config.DatapackMultiImportConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;
import org.apache.seatunnel.web.spi.bean.dto.config.JobEnvConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatapackMultiWorkflowTest {

    @Test
    void createsSameNameTablesWithStringColumnsInCsvOrder() {
        var content = content();
        content.getDatapack().setFiles(List.of(
                file(1L, "reports/Team.CSV", "Z_Ref", "Count", "a_note"),
                file(2L, "Assets.csv", "Asset_ID")));
        content.getTarget().setSchemaName("landing");
        var dag = DagUtil.parseAndCheck(JSONUtils.toJsonString(DatapackMultiWorkflow.assemble(content)));
        var nodes = dag.getNodesAsConfig();

        assertEquals(4, nodes.size());
        assertEquals(2, dag.getEdges().size());
        assertEquals("S3File", nodes.get(0).getString("data.config.pluginName"));
        var fields = nodes.get(0).getConfig("data.config.schema.fields");
        assertEquals(Map.of("Z_Ref", "string", "Count", "string", "a_note", "string"), fields.root().unwrapped());
        assertEquals("Team", nodes.get(1).getString("data.config.table"));
        assertEquals("landing", nodes.get(1).getString("data.config.schemaName"));
        assertEquals("csv_1", nodes.get(1).getString("data.config.pluginInput"));
        assertTrue(nodes.get(1).getBoolean("data.config.autoCreateTable"));
        assertEquals("CREATE_SCHEMA_WHEN_NOT_EXIST", nodes.get(1).getString("data.config.schemaSaveMode"));
        assertEquals("ORIGINAL", nodes.get(1).getString("data.config.fieldIde"));
        assertEquals("Assets", nodes.get(3).getString("data.config.table"));
        assertEquals("csv_2", nodes.get(3).getString("data.config.pluginInput"));

        String graph = JSONUtils.toJsonString(DatapackMultiWorkflow.assemble(content));
        assertTrue(graph.indexOf("Z_Ref") < graph.indexOf("Count") && graph.indexOf("Count") < graph.indexOf("a_note"));
    }

    @Test
    void rejectsConflictingNamesFromDifferentPackagePaths() {
        var content = content();
        content.getDatapack().setFiles(List.of(
                file(1L, "one/Team.csv", "id"), file(2L, "two/Team.CSV", "id")));
        var exception = assertThrows(IllegalArgumentException.class, () -> DatapackMultiWorkflow.assemble(content));
        assertEquals("目标表重复 Team", exception.getMessage());
    }

    @Test
    void assemblesIndependentBranchesWithAndWithoutAFieldMapper() {
        var content = content(row(1L, "team", "team_landing", "id"), row(2L, "asset", "asset_landing", "asset_id"));
        var dag = DagUtil.parseAndCheck(JSONUtils.toJsonString(DatapackMultiWorkflow.assemble(content)));
        var nodes = dag.getNodesAsConfig();
        assertEquals(5, nodes.size());
        assertEquals(3, dag.getEdges().size());
        assertEquals("S3File", nodes.get(0).getString("data.config.pluginName"));
        assertEquals("Jdbc", nodes.get(1).getString("data.connectorType"));
        assertEquals("csv_1", nodes.get(1).getString("data.config.pluginInput"));
        assertEquals("csv_2", nodes.get(3).getString("data.config.pluginInput"));
        assertEquals("mapped_2", nodes.get(4).getString("data.config.pluginInput"));
        assertEquals("asset_landing", nodes.get(4).getString("data.config.table"));
    }

    @Test
    void rejectsTwoFilesWritingTheSameTargetTable() {
        var content = content(row(1L, "team", "landing", "id"), row(2L, "asset", "landing", "id"));
        var exception = assertThrows(IllegalArgumentException.class, () -> DatapackMultiWorkflow.assemble(content));
        assertEquals("目标表重复 landing", exception.getMessage());
    }

    @Test
    void formatsRepeatedConnectorsBeforeJoiningTheirBlocks() {
        var content = content(row(1L, "team", "team_landing", "id"), row(2L, "asset", "asset_landing", "id"));
        var dag = DagUtil.parseAndCheck(JSONUtils.toJsonString(DatapackMultiWorkflow.assemble(content)));
        var source = mock(SourceNodeConfigBuilder.class);
        when(source.nodeType()).thenReturn("source");
        when(source.connectorName(any())).thenReturn("S3File");
        when(source.build(any(), any())).thenReturn(ConfigFactory.parseString("access_key = \"fixture-value\""));
        var sink = mock(SinkNodeConfigBuilder.class);
        when(sink.nodeType()).thenReturn("sink");
        when(sink.connectorName(any())).thenReturn("Jdbc");
        when(sink.build(any(), any())).thenReturn(ConfigFactory.parseString("password = \"fixture-value\""));
        var builder = new HoconConfigBuilder();
        ReflectionTestUtils.setField(builder, "registry", new NodeConfigBuilderRegistry(List.of(source, sink)));
        ReflectionTestUtils.setField(builder, "envConfigBuilder", new EnvConfigBuilder(List.of()));

        String preview = builder.build(dag, new JobEnvConfig(), null, null, "3.0.0", true,
                block -> ConfigFactory.parseString(block).root().render().replace("fixture-value", "******"));

        assertEquals(2, preview.split("\"S3File\"", -1).length - 1);
        assertEquals(2, preview.split("\"Jdbc\"", -1).length - 1);
        assertTrue(preview.contains("csv_1") && preview.contains("csv_2"));
        assertFalse(preview.contains("fixture-value"));
    }

    private static GuideMultiJobContent content(DatapackMultiImportConfig.CsvMapping... rows) {
        var config = new DatapackMultiImportConfig();
        config.setPackageName("fixture.datapack");
        config.setPackageResourceId(4L);
        config.setPackageFingerprint("a".repeat(64));
        config.setMappings(List.of(rows));
        var target = new GuideMultiJobContent.WorkflowTargetConfig();
        target.setDatasourceId("7");
        target.setDbType("MYSQL");
        target.setConnectorType("Jdbc");
        target.setPluginName("JDBC-MYSQL");
        target.setDatabase("test");
        var result = new GuideMultiJobContent();
        result.setDatapack(config);
        result.setTarget(target);
        return result;
    }

    private static DatapackMultiImportConfig.CsvMapping row(Long id, String name, String table, String field) {
        var row = new DatapackMultiImportConfig.CsvMapping();
        row.setFileName(name + ".csv");
        row.setFileResourceId(id);
        row.setFields(Map.of("id", "long"));
        row.setFieldMapping(Map.of("id", field));
        row.setTargetTable(table);
        return row;
    }

    private static DatapackMultiImportConfig.CsvFile file(Long id, String name, String... columns) {
        var file = new DatapackMultiImportConfig.CsvFile();
        file.setFileName(name);
        file.setFileResourceId(id);
        file.setColumnNames(List.of(columns));
        return file;
    }
}
