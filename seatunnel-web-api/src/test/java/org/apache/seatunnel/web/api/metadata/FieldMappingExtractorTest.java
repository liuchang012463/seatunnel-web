package org.apache.seatunnel.web.api.metadata;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldMappingExtractorTest {

    @Test
    void readsTheFieldMapperOfATransform() {
        Map<String, Set<String>> mappings = FieldMappingExtractor.extract(config("""
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
        assertEquals(Set.of("source_order_id"), mappings.get("order_id"));
        assertEquals(Set.of("source_created_at"), mappings.get("created_at"));
    }

    @Test
    void readsTheProjectionOfASqlTransform() {
        Map<String, Set<String>> mappings = FieldMappingExtractor.extract(config("""
                transform {
                  joined {
                    query="select o.id as order_id, c.name as customer_name from om_demo.orders o join om_demo.customers c on o.user_id = c.user_id"
                  }
                }
                """));

        // The OpenMetadata channel only needs the column names, without the table qualifier.
        assertEquals(Set.of("id"), mappings.get("order_id"));
        assertEquals(Set.of("name"), mappings.get("customer_name"));
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
    void ignoresAProjectionWithAStar() {
        Map<String, Set<String>> mappings = FieldMappingExtractor.extract(config("""
                transform {
                  copied {
                    query="select * from om_demo.orders"
                  }
                }
                """));

        assertTrue(mappings.isEmpty());
    }

    @Test
    void readsNothingFromAnEmptyConfiguration() {
        assertTrue(FieldMappingExtractor.extract(config("parallelism = 1")).isEmpty());
        assertTrue(FieldMappingExtractor.extract(null).isEmpty());
    }

    private static Config config(String hocon) {
        return ConfigFactory.parseString(hocon);
    }
}
