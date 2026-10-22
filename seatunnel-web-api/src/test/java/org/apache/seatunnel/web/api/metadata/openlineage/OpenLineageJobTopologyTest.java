package org.apache.seatunnel.web.api.metadata.openlineage;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenLineageJobTopologyTest {

    @Test
    void readsTheSingleTableOfAGuideJob() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    Jdbc {
                        url="jdbc:mysql://127.0.0.1:3306/db"
                        table_path="db.orders"
                    }
                }
                sink {
                    Jdbc {
                        table="ods.orders_dwd"
                    }
                }
                """));

        assertEquals(1, topology.sources().size());
        assertEquals("source.Jdbc", topology.sources().get(0).configPath());
        assertEquals(List.of("db.orders"), topology.sources().get(0).tables());
        assertEquals("jdbc:mysql://127.0.0.1:3306/db", topology.sources().get(0).url());
        assertEquals(List.of("ods.orders_dwd"), topology.sinks().get(0).tables());
    }

    @Test
    void readsEveryTableOfAMultiTableJob() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    Jdbc {
                        table_list=["db.orders","db.customers"]
                    }
                }
                sink {
                    Jdbc {
                        sink_table_list=["ods.orders_dwd","ods.customers_dwd"]
                    }
                }
                """));

        assertEquals(List.of("db.orders", "db.customers"), topology.sources().get(0).tables());
        assertEquals(List.of("ods.orders_dwd", "ods.customers_dwd"),
                topology.sinks().get(0).tables());
    }

    @Test
    void readsEveryPluginOfAMultiSourceJob() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source = [
                  { Jdbc { table_path="db.orders" } },
                  { Kafka { topic="orders-topic" } }
                ]
                sink {
                    Jdbc { table="ods.orders_dwd" }
                }
                """));

        assertEquals(2, topology.sources().size());
        assertEquals("source[0].Jdbc", topology.sources().get(0).configPath());
        assertEquals(List.of("db.orders"), topology.sources().get(0).tables());
        assertEquals("source[1].Kafka", topology.sources().get(1).configPath());
        // A topic is not a table, so the plugin contributes no dataset.
        assertTrue(topology.sources().get(1).tables().isEmpty());
    }

    @Test
    void readsThePluginThatNamesItselfInline() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    plugin_name="Jdbc"
                    table_path="db.orders"
                    dataSourceId="42"
                }
                """));

        assertEquals(1, topology.sources().size());
        assertEquals("source", topology.sources().get(0).configPath());
        assertEquals(42L, topology.sources().get(0).datasourceId());
    }

    @Test
    void qualifiesTheTableWithTheDatabaseWhenTheyAreSeparate() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    Jdbc {
                        database="om_demo"
                        table_name="wx_mp_user"
                    }
                }
                """));

        assertEquals(List.of("om_demo.wx_mp_user"), topology.sources().get(0).tables());
    }

    @Test
    void skipsDynamicTablePlaceholders() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    Jdbc {
                        table_path="${table_name}"
                    }
                }
                """));

        assertTrue(topology.sources().get(0).tables().isEmpty());
    }

    @Test
    void readsNoPluginFromAnEmptyConfiguration() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                parallelism = 1
                """));

        assertTrue(topology.sources().isEmpty());
        assertTrue(topology.sinks().isEmpty());
        assertTrue(OpenLineageJobTopology.parse(null).sources().isEmpty());
        assertTrue(OpenLineageJobTopology.parse(null).sinks().isEmpty());
    }

    private static Config config(String hocon) {
        return ConfigFactory.parseString(hocon);
    }
}
