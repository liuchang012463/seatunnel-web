package org.apache.seatunnel.web.core.job.handler.script;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScriptJobDefinitionParserTest {

    private final ScriptJobDefinitionParser parser = new ScriptJobDefinitionParser();

    @Test
    void extractsEveryDatasourceIdFromScriptSourceAndSinkBlocks() {
        String hocon = """
                source = [
                  { plugin_name = "Jdbc", dataSourceId = 11 },
                  { plugin_name = "Jdbc", datasource_id = "12" }
                ]
                sink = [
                  { plugin_name = "Jdbc", dataSourceId = 21 },
                  { plugin_name = "Jdbc", datasourceId = 22 }
                ]
                """;

        assertEquals(Set.of(11L, 12L), parser.extractDatasourceIds(hocon, "source"));
        assertEquals(Set.of(21L, 22L), parser.extractDatasourceIds(hocon, "sink"));
    }
}
