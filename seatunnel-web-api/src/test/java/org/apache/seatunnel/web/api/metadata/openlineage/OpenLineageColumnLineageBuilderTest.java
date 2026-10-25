package org.apache.seatunnel.web.api.metadata.openlineage;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.web.api.metadata.FieldMappingExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenLineageColumnLineageBuilderTest {

    private static final OpenLineageSnapshot.Dataset ORDERS =
            new OpenLineageSnapshot.Dataset(7L, "MYSQL", "bi_src_mysql", "om_demo.orders");
    private static final OpenLineageSnapshot.Dataset CUSTOMERS =
            new OpenLineageSnapshot.Dataset(7L, "MYSQL", "bi_src_mysql", "om_demo.customers");
    private static final OpenLineageSnapshot.Dataset SINK =
            new OpenLineageSnapshot.Dataset(11L, "DORIS", "bi_tidb_doris", "ods.orders_dwd");

    @Test
    void attributesEveryColumnToTheOnlySourceTable() {
        List<OpenLineageSnapshot.OutputLineage> lineages = build(
                List.of(ORDERS),
                List.of(SINK),
                """
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
                """);

        assertEquals(1, lineages.size());
        Map<String, List<OpenLineageSnapshot.SourceColumn>> columns = lineages.get(0).columns();
        assertEquals(2, columns.size());
        assertEquals("source_order_id", columns.get("order_id").get(0).column());
        assertEquals(ORDERS, columns.get("order_id").get(0).dataset());
        assertEquals("source_created_at", columns.get("created_at").get(0).column());
    }

    @Test
    void dropsAColumnWhoseSourceTableCannotBeResolved() {
        List<OpenLineageSnapshot.OutputLineage> lineages = build(
                List.of(ORDERS, CUSTOMERS),
                List.of(SINK),
                """
                source {
                    Jdbc { table_list=["om_demo.orders","om_demo.customers"] }
                }
                transform = [{
                  FieldMapper {
                    plugin_input = source
                    plugin_output = sink
                    field_mapper {
                      source_order_id = order_id
                    }
                  }
                }]
                sink {
                    Jdbc { table="ods.orders_dwd" }
                }
                """);

        // Two source tables and a field mapper that names no table: the column stays unresolved
        // and the event keeps its table level lineage.
        assertTrue(lineages.isEmpty());
    }

    @Test
    void attributesAJoinColumnToItsOwnTableByAlias() {
        List<OpenLineageSnapshot.OutputLineage> lineages = build(
                List.of(ORDERS, CUSTOMERS),
                List.of(SINK),
                """
                source {
                    Jdbc { table_path="om_demo.orders" }
                }
                source2 {
                    Jdbc { table_path="om_demo.customers" }
                }
                transform {
                  joined {
                    source_table_name="om_demo.orders"
                    query="select o.id as order_id, c.name as customer_name from om_demo.orders o join om_demo.customers c on o.user_id = c.user_id"
                  }
                }
                sink {
                    Jdbc { table="ods.orders_dwd" }
                }
                """);

        assertEquals(1, lineages.size());
        Map<String, List<OpenLineageSnapshot.SourceColumn>> columns = lineages.get(0).columns();
        assertEquals(ORDERS, columns.get("order_id").get(0).dataset());
        assertEquals("id", columns.get("order_id").get(0).column());
        assertEquals(CUSTOMERS, columns.get("customer_name").get(0).dataset());
        assertEquals("name", columns.get("customer_name").get(0).column());
    }

    @Test
    void attributesAColumnToTheSourceTableTheTransformNames() {
        List<OpenLineageSnapshot.OutputLineage> lineages = build(
                List.of(ORDERS, CUSTOMERS),
                List.of(SINK),
                """
                source {
                    Jdbc { table_path="om_demo.orders" }
                }
                source2 {
                    Jdbc { table_path="om_demo.customers" }
                }
                transform {
                  joined {
                    source_table_name="om_demo.customers"
                    query="select id as customer_id from om_demo.customers"
                  }
                }
                sink {
                    Jdbc { table="ods.orders_dwd" }
                }
                """);

        assertEquals(1, lineages.size());
        Map<String, List<OpenLineageSnapshot.SourceColumn>> columns = lineages.get(0).columns();
        assertEquals(CUSTOMERS, columns.get("customer_id").get(0).dataset());
    }

    @Test
    void keepsOneOutputColumnWithTwoSources() {
        List<OpenLineageSnapshot.OutputLineage> lineages = build(
                List.of(ORDERS, CUSTOMERS),
                List.of(SINK),
                """
                source {
                    Jdbc { table_path="om_demo.orders" }
                }
                source2 {
                    Jdbc { table_path="om_demo.customers" }
                }
                transform {
                  joined {
                    query="select o.id as order_id, c.id as customer_id from om_demo.orders o join om_demo.customers c on o.user_id = c.user_id"
                  }
                }
                sink {
                    Jdbc { table="ods.orders_dwd" }
                }
                """);

        Map<String, List<OpenLineageSnapshot.SourceColumn>> columns = lineages.get(0).columns();
        assertEquals(2, columns.size());
        assertEquals(ORDERS, columns.get("order_id").get(0).dataset());
        assertEquals(CUSTOMERS, columns.get("customer_id").get(0).dataset());
    }

    @Test
    void bindsEachOutputDatasetToItsOwnColumns() {
        OpenLineageSnapshot.Dataset secondSink =
                new OpenLineageSnapshot.Dataset(11L, "DORIS", "bi_tidb_doris", "ods.customers_dwd");
        List<OpenLineageSnapshot.OutputLineage> lineages = build(
                List.of(ORDERS),
                List.of(SINK, secondSink),
                """
                source {
                    Jdbc { table_path="om_demo.orders" }
                }
                transform = [{
                  FieldMapper {
                    plugin_input = source
                    plugin_output = sink
                    field_mapper {
                      source_order_id = order_id
                    }
                  }
                }]
                sink {
                    Jdbc { table="ods.orders_dwd" }
                }
                """);

        // Two output datasets and a transform that names no result table: the target table of
        // the column is unknown, so the event keeps its table level lineage.
        assertTrue(lineages.isEmpty());
    }

    @Test
    void bindsTheColumnsToTheOutputTableTheTransformNames() {
        OpenLineageSnapshot.Dataset secondSink =
                new OpenLineageSnapshot.Dataset(11L, "DORIS", "bi_tidb_doris", "ods.customers_dwd");
        List<OpenLineageSnapshot.OutputLineage> lineages = build(
                List.of(ORDERS),
                List.of(SINK, secondSink),
                """
                source {
                    Jdbc { table_path="om_demo.orders" }
                }
                transform {
                  mapped {
                    result_table_name="ods.customers_dwd"
                    query="select id as order_id from om_demo.orders"
                  }
                }
                sink {
                    Jdbc { table="ods.orders_dwd" }
                }
                """);

        assertEquals(1, lineages.size());
        assertEquals(secondSink, lineages.get(0).output());
        assertEquals(ORDERS, lineages.get(0).columns().get("order_id").get(0).dataset());
    }

    @Test
    void dropsAColumnWhoseTableNameIsAmbiguousAcrossSchemas() {
        OpenLineageSnapshot.Dataset otherSchema =
                new OpenLineageSnapshot.Dataset(9L, "MYSQL", "other_mysql", "other.wx_mp_user");
        OpenLineageSnapshot.Dataset chatbi =
                new OpenLineageSnapshot.Dataset(7L, "MYSQL", "bi_src_mysql", "chatbi.wx_mp_user");
        List<OpenLineageSnapshot.OutputLineage> lineages = build(
                List.of(chatbi, otherSchema),
                List.of(SINK),
                """
                source {
                    Jdbc { table_path="chatbi.wx_mp_user" }
                }
                source2 {
                    Jdbc { table_path="other.wx_mp_user" }
                }
                transform {
                  joined {
                    source_table_name="wx_mp_user"
                    query="select id as user_id from wx_mp_user"
                  }
                }
                sink {
                    Jdbc { table="ods.orders_dwd" }
                }
                """);

        // The mapping names the table without its schema and two datasets carry that name.
        // Attaching the column to either of them would be a guess, so it stays unresolved and
        // the event keeps its table level lineage.
        assertTrue(lineages.isEmpty());
    }

    @Test
    void dropsAColumnWhoseExactTablePathExistsInTwoDatasources() {
        OpenLineageSnapshot.Dataset samePathOtherDatasource =
                new OpenLineageSnapshot.Dataset(8L, "MYSQL", "other_mysql", "om_demo.orders");
        List<OpenLineageSnapshot.OutputLineage> lineages = build(
                List.of(ORDERS, samePathOtherDatasource),
                List.of(SINK),
                """
                source {
                    Jdbc { table_path="om_demo.orders" }
                }
                source2 {
                    Jdbc { table_path="om_demo.orders" }
                }
                transform {
                  joined {
                    source_table_name="om_demo.orders"
                    query="select id as order_id from om_demo.orders"
                  }
                }
                sink {
                    Jdbc { table="ods.orders_dwd" }
                }
                """);

        assertTrue(lineages.isEmpty());
    }

    @Test
    void resolvesTheGeneratedSqlAliasesToTheirDatasets() {
        Config config = ConfigFactory.parseString("""
                source {
                    Jdbc {
                        plugin_output="source-1"
                        table_path="om_demo.orders"
                    }
                }
                source2 {
                    Jdbc {
                        plugin_output="source-2"
                        table_path="om_demo.customers"
                    }
                }
                transform {
                  Sql {
                    plugin_input="source-1"
                    plugin_output="sink-1"
                    query="SELECT `source_order_id` AS `order_id` FROM `source-1`"
                  }
                }
                sink {
                    Jdbc {
                        plugin_input="sink-1"
                        table="ods.orders_dwd"
                    }
                }
                """);

        List<OpenLineageSnapshot.OutputLineage> lineages = OpenLineageColumnLineageBuilder.build(
                List.of(ORDERS, CUSTOMERS),
                List.of(SINK),
                FieldMappingExtractor.extractDetailed(config),
                Map.of("source-1", List.of(ORDERS), "source-2", List.of(CUSTOMERS)),
                Map.of("sink-1", List.of(SINK)));

        assertEquals(1, lineages.size());
        assertEquals(SINK, lineages.get(0).output());
        assertEquals(ORDERS, lineages.get(0).columns().get("order_id").get(0).dataset());
        assertEquals("source_order_id", lineages.get(0).columns().get("order_id").get(0).column());
    }

    @Test
    void doesNotBindAQualifiedColumnWhoseAliasIsMissing() {
        List<OpenLineageSnapshot.OutputLineage> lineages = build(
                List.of(ORDERS),
                List.of(SINK),
                """
                source {
                    Jdbc { table_path="om_demo.orders" }
                }
                transform {
                  Sql {
                    query="select x.id as order_id from om_demo.orders o"
                  }
                }
                sink {
                    Jdbc { table="ods.orders_dwd" }
                }
                """);

        // The alias x is not in the FROM clause; binding the column to the only input would be
        // a guess, so it stays unresolved.
        assertTrue(lineages.isEmpty());
    }

    @Test
    void attributesAJoinColumnToItsOwnTable() {
        List<OpenLineageSnapshot.OutputLineage> lineages = build(
                List.of(ORDERS, CUSTOMERS),
                List.of(SINK),
                """
                source {
                    Jdbc { table_list=["om_demo.orders","om_demo.customers"] }
                }
                transform {
                  joined {
                    query="select o.id as order_id, c.name as customer_name from om_demo.orders o join om_demo.customers c on o.id = c.id"
                  }
                }
                sink {
                    Jdbc { table="ods.orders_dwd" }
                }
                """);

        assertEquals(1, lineages.size());
        Map<String, List<OpenLineageSnapshot.SourceColumn>> columns = lineages.get(0).columns();
        assertEquals(ORDERS, columns.get("order_id").get(0).dataset());
        assertEquals(CUSTOMERS, columns.get("customer_name").get(0).dataset());
    }

    private static List<OpenLineageSnapshot.OutputLineage> build(
            List<OpenLineageSnapshot.Dataset> inputs,
            List<OpenLineageSnapshot.Dataset> outputs,
            String hocon) {
        Config config = ConfigFactory.parseString(hocon);
        return OpenLineageColumnLineageBuilder.build(
                inputs, outputs, FieldMappingExtractor.extractDetailed(config));
    }
}
