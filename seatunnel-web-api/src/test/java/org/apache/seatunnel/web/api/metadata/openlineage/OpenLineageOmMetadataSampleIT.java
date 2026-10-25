package org.apache.seatunnel.web.api.metadata.openlineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.seatunnel.web.api.metadata.FieldMappingExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MANUAL sample of complex lineage, built from the metadata that really exists in OpenMetadata.
 *
 * <p>
 * Every table and every column of the samples exists in the OpenMetadata 2.0.4 instance, and the
 * dataset identity (namespace, datasource id, service name) is the one the bridge publishes: the
 * namespace is the local datasource name, which OpenMetadata keeps as the service display name, and
 * the service name is the OpenMetadata database service of that datasource.
 * </p>
 *
 * <p>
 * The events are rendered by the production code path - job configuration to topology to column
 * attribution to event factory - so the samples show what the bridge really emits for a many to
 * one, a one to many, a many to many and a cross datasource job.
 * </p>
 *
 * <p>
 * Run with {@code LINEAGE_BRIDGE_SAMPLE=true}. Regular test runs skip it.
 * </p>
 */
@EnabledIfEnvironmentVariable(named = "LINEAGE_BRIDGE_SAMPLE", matches = "true")
class OpenLineageOmMetadataSampleIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The MySQL datasource that holds the chatbi schema. */
    private static final String CHATBI_NAMESPACE = "82.157.22.233_13306";
    private static final long CHATBI_DATASOURCE_ID = 22416919044448L;
    private static final String CHATBI_SERVICE = "st_ds_22416919044448";

    /** The second MySQL datasource, used by the cross datasource sample. */
    private static final String MYDB_NAMESPACE = "82.157.22.233_23306";
    private static final long MYDB_DATASOURCE_ID = 22422296575072L;
    private static final String MYDB_SERVICE = "st_ds_22422296575072";

    @Test
    void produceTheComplexLineageSamplesAndReadThemBack() throws Exception {
        String bootstrap = env("LINEAGE_BRIDGE_KAFKA_BOOTSTRAP_SERVERS",
                "127.0.0.1:19092,127.0.0.1:19093,127.0.0.1:19094");
        String topic = env("LINEAGE_BRIDGE_SOURCE_TOPIC", "meta_lineage_topic");
        String username = env("LINEAGE_BRIDGE_KAFKA_SASL_USERNAME", "kafka-admin");
        String password = env("LINEAGE_BRIDGE_KAFKA_SASL_PASSWORD", "");

        LineageBridgeProperties properties = new LineageBridgeProperties();
        properties.getKafka().setEnabled(true);
        properties.getKafka().setBootstrapServers(bootstrap);
        properties.getKafka().setSourceTopic(topic);
        properties.getKafka().setSaslUsername(username);
        properties.getKafka().setSaslPassword(password);
        // The broker may have automatic topic creation turned off.
        new OpenLineageTopicProvisioner(properties).ensureTopic(topic);

        List<String> runIds = new ArrayList<>();
        Map<String, String> events = new LinkedHashMap<>();
        for (Sample sample : samples()) {
            String eventJson = render(sample);
            events.put(sample.name(), eventJson);
            runIds.add(MAPPER.readTree(eventJson).path("run").path("runId").asText());
        }

        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(OpenLineageKafkaBridge.producerProperties(properties))) {
            for (Map.Entry<String, String> event : events.entrySet()) {
                var metadata = producer.send(new ProducerRecord<>(topic, null, event.getValue())).get();
                JsonNode node = MAPPER.readTree(event.getValue());
                System.out.println("[OM-SAMPLE] produced " + event.getKey()
                        + " partition=" + metadata.partition() + " offset=" + metadata.offset()
                        + " runId=" + node.path("run").path("runId").asText()
                        + " inputs=" + node.path("inputs").size()
                        + " outputs=" + node.path("outputs").size());
            }
        }

        Map<String, String> consumed = consume(topic, runIds, properties);
        for (Map.Entry<String, String> event : events.entrySet()) {
            String runId = MAPPER.readTree(event.getValue()).path("run").path("runId").asText();
            String back = consumed.get(runId);
            assertNotNull(back, "the sample " + event.getKey() + " was not seen back from " + topic);
            assertEquals(event.getValue().length(), back.length());
            System.out.println("[OM-SAMPLE] verified " + event.getKey() + " runId=" + runId);
        }
        writeSamples(events);
    }

    /** One job of the sample set: a configuration, the datasets it reads and writes, its job name. */
    private record Sample(
            String name,
            String jobName,
            String runtimeConfig,
            List<OpenLineageSnapshot.Dataset> inputs,
            List<OpenLineageSnapshot.Dataset> outputs) {
    }

    private static List<Sample> samples() {
        List<Sample> samples = new ArrayList<>();
        samples.add(manyToOne());
        samples.add(oneToMany());
        samples.add(manyToMany());
        samples.add(crossDatasource());
        samples.add(crossSchemaManyToOne());
        return samples;
    }

    /**
     * N:1 - two source tables of the chatbi schema are merged into the user table. The name column
     * of the result reads from both source tables, which is the many to one column edge.
     */
    private static Sample manyToOne() {
        OpenLineageSnapshot.Dataset users = chatbi("om_demo.chatbi.wx_mp_user");
        OpenLineageSnapshot.Dataset authorize = chatbi("om_demo.chatbi.ac_user_authorize");
        OpenLineageSnapshot.Dataset target = chatbi("om_demo.chatbi.ac_user");
        return new Sample(
                "many-to-one-chatbi-user-merge",
                "chatbi_merge_ac_user",
                """
                source = [
                  { Jdbc {
                      url="jdbc:mysql://82.157.22.233:13306/om_demo"
                      table_path="om_demo.chatbi.wx_mp_user"
                  } },
                  { Jdbc {
                      url="jdbc:mysql://82.157.22.233:13306/om_demo"
                      table_path="om_demo.chatbi.ac_user_authorize"
                  } }
                ]
                transform {
                  merged {
                    source_table_name="om_demo.chatbi.wx_mp_user"
                    result_table_name="om_demo.chatbi.ac_user"
                    query="select u.id as id, u.nickname as name, a.user_name as name, u.head_img_url as avatar, u.sex as gender, a.type as reg_channel, u.update_time as last_login_time, u.create_time as create_time, u.update_time as update_time from om_demo.chatbi.wx_mp_user u join om_demo.chatbi.ac_user_authorize a on u.user_id = a.user_id"
                  }
                }
                sink {
                    Jdbc {
                        url="jdbc:mysql://82.157.22.233:13306/om_demo"
                        table="om_demo.chatbi.ac_user"
                    }
                }
                """,
                List.of(users, authorize),
                List.of(target));
    }

    /**
     * 1:N - one source table feeds two target tables of the chatbi schema, each with its own
     * column lineage.
     */
    private static Sample oneToMany() {
        OpenLineageSnapshot.Dataset users = chatbi("om_demo.chatbi.wx_mp_user");
        OpenLineageSnapshot.Dataset target = chatbi("om_demo.chatbi.ac_user");
        OpenLineageSnapshot.Dataset token = chatbi("om_demo.chatbi.ac_user_token");
        return new Sample(
                "one-to-many-chatbi-fan-out",
                "chatbi_fanout_user_and_token",
                """
                source = [
                  { Jdbc {
                      url="jdbc:mysql://82.157.22.233:13306/om_demo"
                      table_path="om_demo.chatbi.wx_mp_user"
                  } }
                ]
                transform {
                  to_user {
                    result_table_name="om_demo.chatbi.ac_user"
                    query="select id as id, nickname as name, head_img_url as avatar, sex as gender, create_time as create_time, update_time as update_time from om_demo.chatbi.wx_mp_user"
                  }
                  to_token {
                    result_table_name="om_demo.chatbi.ac_user_token"
                    query="select id as id, user_id as user_id, create_time as create_time, update_time as update_time from om_demo.chatbi.wx_mp_user"
                  }
                }
                sink = [
                  { Jdbc {
                      url="jdbc:mysql://82.157.22.233:13306/om_demo"
                      table="om_demo.chatbi.ac_user"
                  } },
                  { Jdbc {
                      url="jdbc:mysql://82.157.22.233:13306/om_demo"
                      table="om_demo.chatbi.ac_user_token"
                  } }
                ]
                """,
                List.of(users),
                List.of(target, token));
    }

    /**
     * N:M - two source tables feed two target tables. The statistic table has no columns in
     * OpenMetadata, so it can only carry table level lineage.
     */
    private static Sample manyToMany() {
        OpenLineageSnapshot.Dataset users = chatbi("om_demo.chatbi.ac_user");
        OpenLineageSnapshot.Dataset authorize = chatbi("om_demo.chatbi.ac_user_authorize");
        OpenLineageSnapshot.Dataset stat = chatbi("om_demo.chatbi.ac_user_stat");
        OpenLineageSnapshot.Dataset target = chatbi("om_demo.chatbi.ac_user");
        return new Sample(
                "many-to-many-chatbi-user-and-stat",
                "chatbi_merge_user_and_stat",
                """
                source = [
                  { Jdbc {
                      url="jdbc:mysql://82.157.22.233:13306/om_demo"
                      table_path="om_demo.chatbi.ac_user"
                  } },
                  { Jdbc {
                      url="jdbc:mysql://82.157.22.233:13306/om_demo"
                      table_path="om_demo.chatbi.ac_user_authorize"
                  } }
                ]
                transform {
                  merged {
                    result_table_name="om_demo.chatbi.ac_user"
                    query="select u.id as id, u.name as name, a.type as reg_channel, u.update_time as update_time from om_demo.chatbi.ac_user u join om_demo.chatbi.ac_user_authorize a on u.id = a.user_id"
                  }
                }
                sink = [
                  { Jdbc {
                      url="jdbc:mysql://82.157.22.233:13306/om_demo"
                      table="om_demo.chatbi.ac_user"
                  } },
                  { Jdbc {
                      url="jdbc:mysql://82.157.22.233:13306/om_demo"
                      table="om_demo.chatbi.ac_user_stat"
                  } }
                ]
                """,
                List.of(users, authorize),
                List.of(target, stat));
    }

    /**
     * Cross datasource - the ammunition table of the chatbi datasource is synchronized into the
     * same table of the second MySQL datasource. The two datasets live in different services, so
     * the namespaces differ.
     */
    private static Sample crossDatasource() {
        OpenLineageSnapshot.Dataset source = new OpenLineageSnapshot.Dataset(
                CHATBI_DATASOURCE_ID, "MYSQL", CHATBI_NAMESPACE, "om_demo.om_demo.ammunition");
        OpenLineageSnapshot.Dataset target = new OpenLineageSnapshot.Dataset(
                MYDB_DATASOURCE_ID, "MYSQL", MYDB_NAMESPACE, "mydb.mydb.ammunition");
        return new Sample(
                "cross-datasource-ammunition-sync",
                "sync_ammunition_between_mysql",
                """
                source = [
                  { Jdbc {
                      url="jdbc:mysql://82.157.22.233:13306/om_demo"
                      table_path="om_demo.om_demo.ammunition"
                  } }
                ]
                transform {
                  copied {
                    result_table_name="mydb.mydb.ammunition"
                    query="select id as id, name as name, caliber as caliber, quantity as quantity, warehouse as warehouse, safety_stock as safety_stock, updated_at as updated_at from om_demo.om_demo.ammunition"
                  }
                }
                sink = [
                  { Jdbc {
                      url="jdbc:mysql://82.157.22.233:23306/mydb"
                      table="mydb.mydb.ammunition"
                  } }
                ]
                """,
                List.of(source),
                List.of(target));
    }

    /**
     * Cross schema, many to one - the adapter collection of two schemas of the same database is
     * merged into the adapter collection of a third schema. All three tables exist in
     * OpenMetadata with the same 21 columns.
     */
    private static Sample crossSchemaManyToOne() {
        OpenLineageSnapshot.Dataset testSchema = chatbi("om_demo.data_platform_test.adapter_collection");
        OpenLineageSnapshot.Dataset dimensionSchema = chatbi("om_demo.data_platform_dimension.adapter_collection");
        OpenLineageSnapshot.Dataset target = chatbi("om_demo.huo_data.adapter_collection");
        return new Sample(
                "cross-schema-adapter-collection-merge",
                "merge_adapter_collection",
                """
                source = [
                  { Jdbc {
                      url="jdbc:mysql://82.157.22.233:13306/om_demo"
                      table_path="om_demo.data_platform_test.adapter_collection"
                  } },
                  { Jdbc {
                      url="jdbc:mysql://82.157.22.233:13306/om_demo"
                      table_path="om_demo.data_platform_dimension.adapter_collection"
                  } }
                ]
                transform {
                  merged {
                    result_table_name="om_demo.huo_data.adapter_collection"
                    query="select t.id as id, t.name as name, t.category as category, t.user_name as user_name, t.create_time as create_time, t.update_time as update_time from om_demo.data_platform_test.adapter_collection t join om_demo.data_platform_dimension.adapter_collection d on t.id = d.id"
                  }
                }
                sink = [
                  { Jdbc {
                      url="jdbc:mysql://82.157.22.233:13306/om_demo"
                      table="om_demo.huo_data.adapter_collection"
                  } }
                ]
                """,
                List.of(testSchema, dimensionSchema),
                List.of(target));
    }

    private static OpenLineageSnapshot.Dataset chatbi(String table) {
        return new OpenLineageSnapshot.Dataset(
                CHATBI_DATASOURCE_ID, "MYSQL", CHATBI_NAMESPACE, table);
    }

    /** Renders one sample through the production code path. */
    private static String render(Sample sample) {
        Config runtimeConfig = ConfigFactory.parseString(sample.runtimeConfig());
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(runtimeConfig);
        List<OpenLineageSnapshot.Dataset> inputs = sample.inputs();
        List<OpenLineageSnapshot.Dataset> outputs = sample.outputs();
        List<OpenLineageSnapshot.OutputLineage> lineages = OpenLineageColumnLineageBuilder.build(
                inputs, outputs, FieldMappingExtractor.extractDetailed(runtimeConfig));

        OpenLineageSnapshot snapshot = new OpenLineageSnapshot(
                OpenLineageEventFactory.EventType.COMPLETE,
                Instant.now(),
                "stweb-om-sample-" + sample.name() + "-" + System.currentTimeMillis(),
                1024L, 86L, sample.jobName(),
                false, "BATCH", "BATCH",
                "RUNNING", null, null,
                inputs,
                outputs,
                lineages);
        ObjectNode event = OpenLineageEventFactory.toRunEvent(snapshot);
        // The tables and their columns are the ones OpenMetadata really knows.
        attachSchema((ArrayNode) event.path("inputs"), inputs);
        attachSchema((ArrayNode) event.path("outputs"), outputs);
        return event.toPrettyString();
    }

    /** Adds the schema facet with the columns OpenMetadata holds for the table. */
    private static void attachSchema(ArrayNode datasets, List<OpenLineageSnapshot.Dataset> tables) {
        for (int index = 0; index < datasets.size() && index < tables.size(); index++) {
            OpenLineageSnapshot.Dataset dataset = tables.get(index);
            JsonNode table = omTable(dataset.namespace(), dataset.name());
            if (table == null || !table.path("columns").isArray() || table.path("columns").isEmpty()) {
                // OpenMetadata does not know the columns of this table: no schema facet at all
                // beats an empty one.
                continue;
            }
            ObjectNode facets = (ObjectNode) datasets.get(index).get("facets");
            ObjectNode schema = facets.putObject("schema");
            schema.put("_producer", "https://github.com/weifuwan/seatunnel-web/lineage-bridge");
            schema.put("_schemaURL", "https://openlineage.io/spec/facets/1-1-1/SchemaDatasetFacet.json");
            ArrayNode fields = schema.putArray("fields");
            for (JsonNode column : table.path("columns")) {
                ObjectNode field = fields.addObject();
                field.put("name", column.path("name").asText());
                field.put("type", column.path("dataType").asText());
            }
        }
    }

    /** The table of the OpenMetadata instance, or null when OpenMetadata does not know it. */
    private static JsonNode omTable(String namespace, String table) {
        String service = omService(namespace);
        if (service == null) {
            return null;
        }
        try {
            HttpResponse<String> response = omGet("/v1/tables/name/" + service + "." + table);
            if (response.statusCode() != 200) {
                return null;
            }
            return MAPPER.readTree(response.body());
        } catch (Exception error) {
            return null;
        }
    }

    /**
     * The OpenMetadata database service of a local datasource. The service display name is the
     * local datasource name, and the service name carries the datasource id.
     */
    private static String omService(String namespace) {
        Map<String, String> services = omServicesByNamespace();
        return services.get(namespace);
    }

    private static Map<String, String> omServicesByNamespace() {
        if (omServices != null) {
            return omServices;
        }
        omServices = new LinkedHashMap<>();
        try {
            HttpResponse<String> response = omGet("/v1/services/databaseServices?limit=100");
            if (response.statusCode() != 200) {
                return omServices;
            }
            for (JsonNode service : MAPPER.readTree(response.body()).path("data")) {
                omServices.put(service.path("displayName").asText(), service.path("name").asText());
            }
        } catch (Exception error) {
            System.out.println("[OM-SAMPLE] could not read the OpenMetadata services: " + error);
        }
        return omServices;
    }

    private static Map<String, String> omServices;

    private static HttpResponse<String> omGet(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(omBase() + path))
                .header("Authorization", "Bearer " + omToken())
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** The access token of the OpenMetadata instance, obtained with the configured credentials. */
    private static String omToken() {
        if (omToken != null) {
            return omToken;
        }
        try {
            String password = Base64.getEncoder().encodeToString(
                    env("OM_PASSWORD", "admin").getBytes(StandardCharsets.UTF_8));
            String body = MAPPER.writeValueAsString(Map.of(
                    "email", env("OM_EMAIL", "admin@open-metadata.org"),
                    "password", password));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(omBase() + "/v1/users/login"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.out.println("[OM-SAMPLE] OpenMetadata login failed: " + response.statusCode());
                return null;
            }
            omToken = MAPPER.readTree(response.body()).path("accessToken").asText();
        } catch (Exception error) {
            System.out.println("[OM-SAMPLE] OpenMetadata login failed: " + error);
            return null;
        }
        return omToken;
    }

    private static String omToken;

    private static String omBase() {
        return env("OM_SERVER_URL", "http://127.0.0.1:18585/api");
    }

    private static Map<String, String> consume(
            String topic, List<String> runIds, LineageBridgeProperties properties) {
        Properties consumerProps = new Properties();
        consumerProps.putAll(OpenLineageKafkaBridge.producerProperties(properties));
        consumerProps.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        consumerProps.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        consumerProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG,
                "stweb-om-sample-" + UUID.randomUUID());
        consumerProps.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Map<String, String> consumed = new LinkedHashMap<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(Collections.singletonList(topic));
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline && consumed.size() < runIds.size()) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(3))) {
                    if (record.value() == null) {
                        continue;
                    }
                    for (String runId : runIds) {
                        if (record.value().contains(runId)) {
                            consumed.put(runId, record.value());
                        }
                    }
                }
            }
        }
        return consumed;
    }

    /** Keeps the produced events next to the other samples, for the consumer side. */
    private static void writeSamples(Map<String, String> events) {
        Path directory = Path.of("..", "docs", "samples");
        if (!Files.isDirectory(directory)) {
            return;
        }
        for (Map.Entry<String, String> event : events.entrySet()) {
            try {
                Files.writeString(directory.resolve("openlineage-om-" + event.getKey() + ".json"),
                        event.getValue() + "\n");
            } catch (Exception error) {
                System.out.println("[OM-SAMPLE] could not store " + event.getKey() + ": " + error);
            }
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
