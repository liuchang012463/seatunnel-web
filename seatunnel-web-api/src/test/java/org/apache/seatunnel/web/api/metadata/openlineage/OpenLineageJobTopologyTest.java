package org.apache.seatunnel.web.api.metadata.openlineage;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenLineageJobTopologyTest {

    /**
     * The same table configured through table_path and through table_name carries the same
     * identity, otherwise the asset side resolves two datasets for one physical table.
     */
    @Test
    void aTablePathIsQualifiedWithTheDatabaseLikeATableName() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    Jdbc {
                        database="om_demo"
                        table_path="orders"
                    }
                }
                sink {
                    Jdbc {
                        database="om_demo"
                        table_name="orders_dwd"
                    }
                }
                """));

        assertEquals(List.of("om_demo.orders"), topology.sources().get(0).tables());
        assertEquals(List.of("om_demo.orders_dwd"), topology.sinks().get(0).tables());
    }

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
        // A topic is the dataset of a message-broker plugin, exactly like a table path.
        assertEquals(List.of("orders-topic"), topology.sources().get(1).tables());
    }

    /** A pattern subscription names a regex, not a dataset: it must not be published. */
    @Test
    void doesNotTreatAPatternSubscriptionAsATopicDataset() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    Kafka {
                        topic="orders.$"
                        pattern=true
                    }
                }
                """));

        assertTrue(topology.sources().get(0).tables().isEmpty());
    }

    /** The flag may also be written as a string, and a plain subscription keeps its topics. */
    @Test
    void readsAPatternFlagWrittenAsAString() {
        OpenLineageJobTopology.Topology pattern = OpenLineageJobTopology.parse(config("""
                source {
                    Kafka {
                        topic="orders.$"
                        pattern="true"
                    }
                }
                """));
        assertTrue(pattern.sources().get(0).tables().isEmpty());

        OpenLineageJobTopology.Topology plain = OpenLineageJobTopology.parse(config("""
                source {
                    Kafka {
                        topic="orders-topic"
                        pattern=false
                    }
                }
                """));
        assertEquals(List.of("orders-topic"), plain.sources().get(0).tables());
    }

    /** The plural topic key is a dataset list like the table list keys. */
    @Test
    void readsEveryTopicOfAPluralTopicList() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    Kafka {
                        topics=["orders-topic", "customers-topic"]
                    }
                }
                """));

        assertEquals(List.of("orders-topic", "customers-topic"),
                topology.sources().get(0).tables());
    }

    /**
     * Older payloads stored the regex itself in "pattern": such a value is a pattern
     * subscription, not a topic name.
     */
    @Test
    void treatsALegacyPatternExpressionAsAPatternSubscription() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    Kafka {
                        topic="orders.$"
                        pattern="orders.$"
                    }
                }
                """));

        assertTrue(topology.sources().get(0).pattern());
        assertTrue(topology.sources().get(0).tables().isEmpty());
    }

    /** A regex-shaped table option is an expression, not a concrete dataset name. */
    @Test
    void doesNotTreatARegexShapedTableAsADataset() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    Jdbc {
                        table="orders-[0-9]+"
                    }
                }
                """));

        assertTrue(topology.sources().get(0).tables().isEmpty());
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

    @Test
    void readsCdcTableNames() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    MySQL-CDC {
                        "table-names"=["inventory.orders","inventory.customers"]
                    }
                }
                sink {
                    Jdbc { table_list=["ods.orders_dwd","ods.customers_dwd"] }
                }
                """));

        assertEquals(List.of("inventory.orders", "inventory.customers"),
                topology.sources().get(0).tables());
        assertEquals(List.of("ods.orders_dwd", "ods.customers_dwd"),
                topology.sinks().get(0).tables());
    }

    @Test
    void readsTheTableOfAQueryOnlySource() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    Jdbc {
                        query="select id, name from om_demo.orders where id > 0"
                    }
                }
                sink {
                    Jdbc { table="ods.orders_dwd" }
                }
                """));

        assertEquals(List.of("om_demo.orders"), topology.sources().get(0).tables());
    }

    @Test
    void qualifiesATableListObjectWithItsDatabase() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    Doris {
                        table_list=[{ database="analytics", table="orders" }]
                    }
                }
                """));

        assertEquals(List.of("analytics.orders"), topology.sources().get(0).tables());
    }

    @Test
    void reportsTheTransformAliasesOfAPlugin() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    Jdbc {
                        plugin_output="source-1"
                        table_path="om_demo.orders"
                    }
                }
                sink {
                    Jdbc {
                        plugin_input="sink-1"
                        table="ods.orders_dwd"
                    }
                }
                """));

        assertEquals("source-1", topology.sources().get(0).outputName());
        assertEquals("sink-1", topology.sinks().get(0).inputName());
    }

    @Test
    void readsEveryTableOfAQueryOnlyJoin() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    Jdbc {
                        query="select o.id, c.name from db.orders o join db.customers c on o.id = c.id"
                    }
                }
                """));

        assertEquals(List.of("db.orders", "db.customers"), topology.sources().get(0).tables());
    }

    @Test
    void doesNotTreatACteSubqueryOrUnionAsATable() {
        assertTrue(OpenLineageJobTopology.parse(config("""
                source { Jdbc { query="with x as (select id from db.t) select id from x" } }
                """)).sources().get(0).tables().isEmpty());
        assertTrue(OpenLineageJobTopology.parse(config("""
                source { Jdbc { query="select id from (select id from db.t) x" } }
                """)).sources().get(0).tables().isEmpty());
        assertTrue(OpenLineageJobTopology.parse(config("""
                source { Jdbc { query="select id from db.a union select id from db.b" } }
                """)).sources().get(0).tables().isEmpty());
    }

    @Test
    void doesNotTreatTableFunctionsOrLateralAsTables() {
        assertTrue(OpenLineageJobTopology.parse(config("""
                source { Jdbc { query="select * from generate_series(1,10)" } }
                """)).sources().get(0).tables().isEmpty());
        assertTrue(OpenLineageJobTopology.parse(config("""
                source { Jdbc { query="select * from unnest(array[1,2]) u" } }
                """)).sources().get(0).tables().isEmpty());
        assertTrue(OpenLineageJobTopology.parse(config("""
                source { Jdbc { query="select * from db.a a, lateral (select id from db.b) x" } }
                """)).sources().get(0).tables().isEmpty());
    }

    @Test
    void readsTheTableAfterAMySqlHashComment() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    Jdbc {
                        query="select id # from orders\\n from db.t"
                    }
                }
                """));

        assertEquals(List.of("db.t"), topology.sources().get(0).tables());
    }

    @Test
    void doesNotTreatPostgreSqlHashOperatorsAsComments() {
        assertEquals(List.of("db.t"), OpenLineageJobTopology.parse(config("""
                source { Jdbc { query="select data #> '{a}' from db.t" } }
                """)).sources().get(0).tables());
        assertEquals(List.of("db.t"), OpenLineageJobTopology.parse(config("""
                source { Jdbc { query="select data #>> '{a}' from db.t" } }
                """)).sources().get(0).tables());
    }

    @Test
    void readsTheTableAfterAQueryComment() {
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(config("""
                source {
                    Jdbc {
                        query="select id /* from the source */ from db.t"
                    }
                }
                """));

        assertEquals(List.of("db.t"), topology.sources().get(0).tables());
    }

    private static Config config(String hocon) {
        return ConfigFactory.parseString(hocon);
    }
}
