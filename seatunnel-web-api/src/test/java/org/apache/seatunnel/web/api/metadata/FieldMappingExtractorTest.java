package org.apache.seatunnel.web.api.metadata;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldMappingExtractorTest {

    @Test
    void readsTheFieldMapperOfATransform() {
        FieldMappingExtractor.Result result = FieldMappingExtractor.extractDetailed(config("""
                source {
                    Jdbc { table_path="om_demo.orders" }
                }
                transform = [{
                  FieldMapper {
                    plugin_input = source
                    plugin_output = sink
                    field_mapper {
                      source_order_id = order_id
                      source_created_at = created_at
                    }
                  }
                }]
                sink {
                    Jdbc { table="ods.orders_dwd" }
                }
                """));

        // The field mapper reads source column = target column.
        assertEquals(Set.of("source_order_id"), sourcesOf(result, "order_id"));
        assertEquals(Set.of("source_created_at"), sourcesOf(result, "created_at"));
    }

    @Test
    void readsTheProjectionOfASqlTransform() {
        FieldMappingExtractor.Result result = FieldMappingExtractor.extractDetailed(config("""
                transform {
                  joined {
                    query="select o.id as order_id, c.name as customer_name from om_demo.orders o join om_demo.customers c on o.user_id = c.user_id"
                  }
                }
                """));

        assertEquals(Set.of("o.id"), sourcesOf(result, "order_id"));
        assertEquals(Set.of("c.name"), sourcesOf(result, "customer_name"));
    }

    @Test
    void keepsTheTableQualifierOfASqlProjectionInTheDetailedExtraction() {
        FieldMappingExtractor.Result result = FieldMappingExtractor.extractDetailed(config("""
                transform {
                  joined {
                    query="select o.id as order_id from om_demo.orders o"
                  }
                }
                """));

        assertEquals(1, result.mappings().size());
        FieldMappingExtractor.ColumnMapping mapping = result.mappings().get(0);
        assertEquals("order_id", mapping.outputColumn());
        assertEquals(Set.of("o.id"), mapping.sourceColumns());
        assertTrue(mapping.qualified());
    }

    @Test
    void reportsTheTablesOfASqlProjection() {
        FieldMappingExtractor.Result result = FieldMappingExtractor.extractDetailed(config("""
                transform {
                  joined {
                    source_table_name="om_demo.orders"
                    result_table_name="tmp.joined"
                    query="select o.id as order_id, c.name as customer_name from om_demo.orders o join om_demo.customers c on o.user_id = c.user_id"
                  }
                }
                """));

        assertEquals(1, result.owners().size());
        FieldMappingExtractor.Owner owner = result.owners().get(0);
        assertEquals("om_demo.orders", owner.sourceTableName());
        assertEquals("tmp.joined", owner.resultTableName());
        assertEquals("om_demo.orders", owner.aliasToTable().get("o"));
        assertEquals("om_demo.customers", owner.aliasToTable().get("c"));
    }

    @Test
    void readsEveryJoinAliasWhenTheSqlUsesAs() {
        FieldMappingExtractor.Result result = FieldMappingExtractor.extractDetailed(config("""
                transform {
                  joined {
                    query="select a.id as order_id, b.name as customer_name from om_demo.orders as a join om_demo.customers as b on a.user_id = b.user_id"
                  }
                }
                """));

        FieldMappingExtractor.Owner owner = result.owners().get(0);
        assertEquals("om_demo.orders", owner.aliasToTable().get("a"));
        assertEquals("om_demo.customers", owner.aliasToTable().get("b"));
    }

    @Test
    void readsTheGeneratedSqlTransformAliases() {
        FieldMappingExtractor.Result result = FieldMappingExtractor.extractDetailed(config("""
                transform {
                  Sql {
                    plugin_input = "source-1"
                    plugin_output = "sink-1"
                    query="SELECT `source_id` AS `target_id` FROM `source-1`"
                  }
                }
                """));

        FieldMappingExtractor.Owner owner = result.owners().get(0);
        assertEquals("source-1", owner.sourceTableName());
        assertEquals("sink-1", owner.resultTableName());
        assertEquals("source-1", owner.aliasToTable().get("source-1"));
        assertEquals(Set.of("source_id"), sourcesOf(result, "target_id"));
    }

    @Test
    void ignoresCastTypeNames() {
        FieldMappingExtractor.Result result = FieldMappingExtractor.extractDetailed(config("""
                transform {
                  casted {
                    query="select cast(`channel_type` as BYTES) as a, cast(`channel_type` as ARRAY<STRING>) as b, cast(`channel_type` as TIMESTAMP_TZ) as c, cast(`channel_type` as DATETIME) as d from db.t"
                  }
                }
                """));

        assertEquals(Set.of("channel_type"), sourcesOf(result, "a"));
        assertEquals(Set.of("channel_type"), sourcesOf(result, "b"));
        assertEquals(Set.of("channel_type"), sourcesOf(result, "c"));
        assertEquals(Set.of("channel_type"), sourcesOf(result, "d"));
    }

    @Test
    void ignoresAProjectionWithAStar() {
        FieldMappingExtractor.Result result = FieldMappingExtractor.extractDetailed(config("""
                transform {
                  copied {
                    query="select * from om_demo.orders"
                  }
                }
                """));

        assertTrue(result.mappings().isEmpty());
    }

    @Test
    void readsNothingFromAnEmptyConfiguration() {
        assertTrue(FieldMappingExtractor.extractDetailed(config("parallelism = 1")).mappings().isEmpty());
        assertTrue(FieldMappingExtractor.extractDetailed(null).mappings().isEmpty());
    }

    private static Set<String> sourcesOf(FieldMappingExtractor.Result result, String outputColumn) {
        return result.mappings().stream()
                .filter(mapping -> outputColumn.equals(mapping.outputColumn()))
                .findFirst()
                .orElseThrow()
                .sourceColumns();
    }

    private static Config config(String hocon) {
        return ConfigFactory.parseString(hocon);
    }
}
