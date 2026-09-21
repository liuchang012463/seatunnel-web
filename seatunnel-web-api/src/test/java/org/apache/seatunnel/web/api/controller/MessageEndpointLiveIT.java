package org.apache.seatunnel.web.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.apache.seatunnel.web.api.message.MessagePayloadValidator;
import org.apache.seatunnel.web.api.message.MessageProperties;
import org.apache.seatunnel.web.api.message.plugin.MessagePluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Full-chain acceptance over the real HTTP contract against a <b>real</b>
 * broker.
 *
 * <p>
 * Unlike {@link MessageControllerTest} this wires the genuine beans — the real
 * {@link MessagePluginManager} (so the RabbitMQ client is discovered through
 * SPI exactly as it is in production), the real
 * {@link MessagePayloadValidator}, and the real controller. Only the HTTP
 * transport is simulated, so the whole
 * {@code HTTP -> controller -> validator -> SPI -> RabbitMQ -> HTTP} path is
 * exercised without needing the database-backed Spring context.
 * </p>
 *
 * <p>
 * Opt-in, so a broker-less machine never goes red:
 * </p>
 *
 * <pre>
 * ./.mvn/mvn21.sh -pl seatunnel-web-api test -DskipTests=false \
 *     -Dtest=MessageEndpointLiveIT -DfailIfNoTests=false \
 *     -Dmessaging.it.enabled=true \
 *     -Dmessaging.it.user=&lt;user&gt; -Dmessaging.it.pass=&lt;password&gt;
 * </pre>
 */
@EnabledIfSystemProperty(named = "messaging.it.enabled", matches = "true")
class MessageEndpointLiveIT {

    private static final String API_KEY = "live-it-key";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String queue;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        queue = "seatunnel.it.http." + UUID.randomUUID().toString().substring(0, 8);

        MessageProperties properties = new MessageProperties();
        properties.setApiKey(API_KEY);

        MessageController controller = new MessageController();
        ReflectionTestUtils.setField(controller, "messagePluginManager",
                new MessagePluginManager());
        ReflectionTestUtils.setField(controller, "messageProperties", properties);
        ReflectionTestUtils.setField(controller, "payloadValidator",
                new MessagePayloadValidator(properties));
        mvc = standaloneSetup(controller).build();

        try (Connection connection = rawFactory().newConnection("seatunnel-web-it-http-setup");
                Channel channel = connection.createChannel()) {
            channel.queueDeclare(queue, true, false, false, null);
        }
    }

    @AfterEach
    void tearDown() {
        try (Connection connection = rawFactory().newConnection("seatunnel-web-it-http-cleanup");
                Channel channel = connection.createChannel()) {
            channel.queueDelete(queue);
        } catch (Exception e) {
            // Best-effort cleanup; never mask the test outcome.
        }
    }

    // -------------------- happy path --------------------

    @Test
    void pushThenPullOverHttpRoundTripsArbitraryJson() throws Exception {
        String payload = "{\"orderId\":\"A001\",\"amount\":12.5,\"note\":\"中文与 emoji 🚀\","
                + "\"items\":[{\"sku\":\"X1\",\"qty\":2}],\"nested\":{\"flag\":true,\"none\":null}}";

        mvc.perform(post("/api/v1/message/push")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connection\":" + connectionJson() + ","
                                + "\"queue\":\"" + queue + "\","
                                + "\"message\":" + payload + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        MvcResult result = mvc.perform(post("/api/v1/message/pull")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connection\":" + connectionJson() + ","
                                + "\"queue\":\"" + queue + "\",\"maxMessages\":10,\"timeoutMs\":5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.returned").value(1))
                .andReturn();

        String body = bodyOf(result);
        // The payload must come back intact: no flattening, no lost structure.
        assertTrue(body.contains("A001"), "订单号必须原样返回: " + body);
        assertTrue(body.contains("中文与 emoji"), "中文必须原样返回: " + body);

        // Compare as trees rather than as raw text: Jackson legitimately escapes
        // non-BMP characters (the emoji becomes a \uD83D\uDE80 surrogate pair),
        // which is valid JSON and semantically identical.
        JsonNode returned = MAPPER.readTree(body).path("data").path("messages").path(0).path("body");
        assertEquals(MAPPER.readTree(payload), returned,
                "任意结构 JSON 必须逐字段无损往返，不得被展平或改写");
    }

    // -------------------- the 1MB limit, end to end --------------------

    @Test
    void oversizePayloadIsRejectedBeforeItReachesTheBroker() throws Exception {
        String oversize = "x".repeat(1_500_000);

        mvc.perform(post("/api/v1/message/push")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connection\":" + connectionJson() + ","
                                + "\"queue\":\"" + queue + "\","
                                + "\"message\":{\"blob\":\"" + oversize + "\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(0)));

        // The real proof: the broker must be untouched. Reading the queue back
        // is the only assertion that cannot be satisfied by a mocked client.
        mvc.perform(post("/api/v1/message/pull")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connection\":" + connectionJson() + ","
                                + "\"queue\":\"" + queue + "\",\"maxMessages\":10,\"timeoutMs\":3000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.returned").value(0));
    }

    // -------------------- failure surfaces --------------------

    @Test
    void wrongPasswordIsReportedAsASanitizedFailure() throws Exception {
        String json = "{\"connection\":{\"host\":\"" + host() + "\",\"port\":" + port()
                + ",\"username\":\"" + user() + "\",\"password\":\"totally-wrong\"},"
                + "\"queue\":\"" + queue + "\",\"message\":{\"a\":1}}";

        MvcResult result = mvc.perform(post("/api/v1/message/push")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(0)))
                .andReturn();

        String body = bodyOf(result);
        assertFalse(body.contains("totally-wrong"), "响应不得回显密码: " + body);
        assertFalse(body.contains("Exception"), "响应应是可读提示而非异常类名: " + body);
    }

    @Test
    void missingApiKeyIsRejectedWithAReal401() throws Exception {
        mvc.perform(post("/api/v1/message/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connection\":" + connectionJson() + ","
                                + "\"queue\":\"" + queue + "\",\"message\":{\"a\":1}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pullFromAnUnknownQueueIsReportedWithoutHttp5xx() throws Exception {
        mvc.perform(post("/api/v1/message/pull")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connection\":" + connectionJson() + ","
                                + "\"queue\":\"seatunnel.it.missing."
                                + UUID.randomUUID().toString().substring(0, 8)
                                + "\",\"maxMessages\":1,\"timeoutMs\":3000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(0)));
    }

    // -------------------- helpers --------------------

    private String connectionJson() {
        return "{\"host\":\"" + host() + "\",\"port\":" + port()
                + ",\"virtualHost\":\"" + vhost() + "\",\"username\":\"" + user()
                + "\",\"password\":\"" + password() + "\"}";
    }

    private String host() {
        return System.getProperty("messaging.it.host", "127.0.0.1");
    }

    private int port() {
        return Integer.getInteger("messaging.it.port", 5672);
    }

    private String vhost() {
        return System.getProperty("messaging.it.vhost", "/");
    }

    private String user() {
        return System.getProperty("messaging.it.user", "guest");
    }

    private String password() {
        return System.getProperty("messaging.it.pass", "guest");
    }

    private ConnectionFactory rawFactory() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host());
        factory.setPort(port());
        factory.setVirtualHost(vhost());
        factory.setUsername(user());
        factory.setPassword(password());
        factory.setConnectionTimeout(8000);
        return factory;
    }

    private String bodyOf(MvcResult result) {
        assertNotNull(result.getResponse());
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
