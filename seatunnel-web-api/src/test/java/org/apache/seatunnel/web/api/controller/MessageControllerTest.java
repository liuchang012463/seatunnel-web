package org.apache.seatunnel.web.api.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.plugin.messaging.api.MessageClient;
import org.apache.seatunnel.plugin.messaging.api.MessageConnectionParam;
import org.apache.seatunnel.plugin.messaging.api.MessageException;
import org.apache.seatunnel.plugin.messaging.api.MessagePullCommand;
import org.apache.seatunnel.plugin.messaging.api.MessagePullItem;
import org.apache.seatunnel.plugin.messaging.api.MessagePullResult;
import org.apache.seatunnel.plugin.messaging.api.MessagePushCommand;
import org.apache.seatunnel.plugin.messaging.api.MessagePushResult;
import org.apache.seatunnel.web.api.message.MessagePayloadValidator;
import org.apache.seatunnel.web.api.message.MessageProperties;
import org.apache.seatunnel.web.api.message.plugin.MessagePluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * HTTP-layer tests for {@link MessageController}.
 *
 * <p>
 * These run the real controller through MockMvc with a stub client, so they
 * cover routing, payload rejection and response shaping
 * <b>without needing a broker or a database</b>. The broker-facing behaviour is
 * covered separately by {@code RabbitMessageClientTest}.
 * </p>
 *
 * <p>
 * The oversize case is the important one: it verifies at the HTTP boundary that
 * an over-limit payload is rejected <i>and never reaches the client</i>, which
 * is the defect this controller previously had.
 * </p>
 */
class MessageControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -------------------- open access --------------------

    @Test
    void acceptsRequestWithoutApiKey() throws Exception {
        RecordingClient client = new RecordingClient();

        mvc(client, props())
                .perform(post("/api/v1/message/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pushBody("{\"orderId\":\"A001\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.success", is(true)));

        assertTrue(client.pushCalled);
    }

    // -------------------- payload size limit --------------------

    @Test
    void rejectsOversizePayloadWithoutReachingTheClient() throws Exception {
        RecordingClient client = new RecordingClient();

        // ~1.5MB, matching the demo script's oversize case. Before the fix this
        // sailed through and was handed to the broker.
        String oversize = "x".repeat(1_500_000);

        mvc(client, props())
                .perform(post("/api/v1/message/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pushBody("{\"blob\":\"" + oversize + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not(0)))
                .andExpect(content().string(containsString("1MB")));

        assertFalse(client.pushCalled,
                "超限消息体必须在进入 MQ 逻辑之前被拒绝");
    }

    @Test
    void acceptsPayloadWithinLimit() throws Exception {
        RecordingClient client = new RecordingClient();

        mvc(client, props())
                .perform(post("/api/v1/message/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pushBody("{\"blob\":\"" + "x".repeat(1000) + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)));

        assertTrue(client.pushCalled);
    }

    // -------------------- push routing --------------------

    @Test
    void pushForwardsExchangeQueueAndPayload() throws Exception {
        RecordingClient client = new RecordingClient();

        mvc(client, props())
                .perform(post("/api/v1/message/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange\":\"ex\",\"queue\":\"q\",\"routingKey\":\"rk\","
                                + "\"persistent\":false,"
                                + "\"message\":{\"orderId\":\"A001\",\"items\":[{\"sku\":\"X1\"}]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.routingKey", is("rk")));

        assertNotNull(client.lastPush);
        // Arbitrary structure must survive as a tree, not be flattened.
        Object payload = client.lastPush.getPayload();
        assertTrue(payload instanceof JsonNode, "payload 应以 JsonNode 树形式透传");
        assertTrue(((JsonNode) payload).has("items"));
        assertFalse(client.lastPush.isPersistent(), "persistent=false 应透传");
    }

    @Test
    void unknownClientTypeIsReported() throws Exception {
        RecordingClient client = new RecordingClient();

        mvc(client, props())
                .perform(post("/api/v1/message/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientType\":\"KAFKA\",\"queue\":\"q\",\"message\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not(0)));

        assertFalse(client.pushCalled);
    }

    // -------------------- pull shaping --------------------

    @Test
    void pullShapesParseableAndUnparseableMessages() throws Exception {
        RecordingClient client = new RecordingClient();
        ObjectNode parsed = MAPPER.createObjectNode();
        parsed.put("orderId", "A001");

        client.pullResult = MessagePullResult.of(List.of(
                MessagePullItem.builder()
                        .deliveryTag(42L)
                        .parseable(true)
                        .body(parsed)
                        .headers(Map.of("source", "erp"))
                        .contentType("application/json")
                        .build(),
                MessagePullItem.builder()
                        .deliveryTag(43L)
                        .parseable(false)
                        .rawBody("not-json")
                        .build()
        ), false, 12L);

        mvc(client, props())
                .perform(post("/api/v1/message/pull")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pullBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.returned", is(2)))
                .andExpect(jsonPath("$.data.truncated", is(false)))
                .andExpect(jsonPath("$.data.elapsedMs", is(12)))
                // Parseable item exposes body, not rawBody.
                .andExpect(jsonPath("$.data.messages[0].parseable", is(true)))
                .andExpect(jsonPath("$.data.messages[0].body.orderId", is("A001")))
                .andExpect(jsonPath("$.data.messages[0].contentType", is("application/json")))
                // Non-JSON item degrades to rawBody instead of being dropped.
                .andExpect(jsonPath("$.data.messages[1].parseable", is(false)))
                .andExpect(jsonPath("$.data.messages[1].rawBody", is("not-json")));
    }

    @Test
    void pullClampsMaxMessagesToServerCeiling() throws Exception {
        RecordingClient client = new RecordingClient();

        MessageProperties props = props();
        props.setMaxBatchSize(100);

        mvc(client, props)
                .perform(post("/api/v1/message/pull")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queue\":\"q\",\"maxMessages\":99999}"))
                .andExpect(status().isOk());

        assertNotNull(client.lastPull);
        assertTrue(client.lastPull.getMaxMessages() <= 100,
                "服务端应硬截断到 max-batch-size，实际: " + client.lastPull.getMaxMessages());
    }

    @Test
    void pullRejectsNonPositiveMaxMessages() throws Exception {
        RecordingClient client = new RecordingClient();

        mvc(client, props())
                .perform(post("/api/v1/message/pull")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queue\":\"q\",\"maxMessages\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not(0)));

        assertFalse(client.pullCalled);
    }

    // -------------------- error mapping --------------------

    @Test
    void clientFailureIsReportedWithoutHttp5xx() throws Exception {
        RecordingClient client = new RecordingClient();
        client.pushResult = MessagePushResult.fail("MQ 连接失败，请检查配置");

        mvc(client, props())
                .perform(post("/api/v1/message/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pushBody("{}")))
                // 200, not 5xx: generic HTTP clients auto-retry 5xx and a
                // retry here would be pointless.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not(0)))
                // Spring writes UTF-8 bytes without a charset parameter (correct for
                // JSON); MockMvc would otherwise decode with its ISO-8859-1 default,
                // so decode the raw bytes explicitly.
                .andExpect(result -> {
                    String body = new String(
                            result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
                    assertTrue(body.contains("MQ 连接失败"),
                            "响应应回传可读的失败原因，实际: " + body);
                });
    }

    // -------------------- exception path & log hygiene --------------------

    /**
     * A caller-fixable failure must reach the log as one line, not a stack trace.
     *
     * <p>
     * {@code getThrowableProxy()} is the precise assertion point: SLF4J only
     * attaches one when the throwable was passed as the final argument, which is
     * exactly the behaviour under test.
     * </p>
     */
    @Test
    void callerFixableExceptionIsReportedWithoutStackTrace() throws Exception {
        RecordingClient client = new RecordingClient();
        client.pushThrows = new MessageException("消息队列未配置");

        ListAppender<ILoggingEvent> logs = captureControllerLogs();

        mvc(client, props())
                .perform(post("/api/v1/message/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pushBody("{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not(0)))
                // Chinese assertions must decode the raw bytes as UTF-8: Spring
                // writes UTF-8 bytes without a charset parameter (correct for
                // JSON), while MockMvc would decode with its ISO-8859-1 default.
                .andExpect(result -> assertTrue(
                        new String(result.getResponse().getContentAsByteArray(),
                                StandardCharsets.UTF_8).contains("消息队列未配置"),
                        "可读的失败原因应回传调用方"));

        assertEquals(1, logs.list.size(), "失败应只记一条日志，而不是新增一条");
        ILoggingEvent event = logs.list.get(0);
        assertTrue(event.getFormattedMessage().contains("消息队列未配置"),
                "日志应带上可读原因，实际: " + event.getFormattedMessage());
        assertNull(event.getThrowableProxy(),
                "调用方可修复的错误不应打堆栈——一个配置错的调用方会把日志刷爆");
    }

    /**
     * An unexpected exception is the opposite case: the trace is the only useful
     * artefact, and the caller must still not see the internal detail.
     */
    @Test
    void unexpectedExceptionKeepsStackTraceAndStaysSanitized() throws Exception {
        RecordingClient client = new RecordingClient();
        client.pushThrows = new IllegalStateException("boom: internal topology");

        ListAppender<ILoggingEvent> logs = captureControllerLogs();

        mvc(client, props())
                .perform(post("/api/v1/message/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pushBody("{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not(0)))
                .andExpect(result -> assertFalse(
                        new String(result.getResponse().getContentAsByteArray(),
                                StandardCharsets.UTF_8).contains("boom"),
                        "非插件异常不得把内部细节回传调用方"));

        assertNotNull(logs.list.get(0).getThrowableProxy(),
                "意外异常必须保留堆栈，否则线上无法定位");
    }

    // -------------------- helpers --------------------

    private final List<Runnable> logDetachers = new ArrayList<>();

    /**
     * Attach a list appender to the controller's logger so a test can assert on
     * what was logged. {@link #detachLogAppenders()} removes it afterwards —
     * otherwise appenders accumulate on the shared logger across tests.
     */
    private ListAppender<ILoggingEvent> captureControllerLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(MessageController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logDetachers.add(() -> logger.detachAppender(appender));
        return appender;
    }

    @AfterEach
    void detachLogAppenders() {
        logDetachers.forEach(Runnable::run);
        logDetachers.clear();
    }

    private MessageProperties props() {
        return new MessageProperties();
    }

    private MockMvc mvc(MessageClient client, MessageProperties props) {
        MessageController controller = new MessageController();
        ReflectionTestUtils.setField(controller, "messagePluginManager", new StubPluginManager(client));
        ReflectionTestUtils.setField(controller, "messageProperties", props);
        ReflectionTestUtils.setField(controller, "payloadValidator", new MessagePayloadValidator(props));
        return standaloneSetup(controller).build();
    }

    private String pushBody(String messageJson) {
        return "{\"queue\":\"order.sync\",\"message\":" + messageJson + "}";
    }

    private String pullBody() {
        return "{\"queue\":\"order.sync\",\"maxMessages\":10}";
    }

    /**
     * Overrides client lookup so no real broker is involved, while leaving the
     * real SPI discovery in the parent constructor intact.
     */
    private static final class StubPluginManager extends MessagePluginManager {

        private final MessageClient client;

        StubPluginManager(MessageClient client) {
            this.client = client;
        }

        @Override
        public MessageClient getClient(String name) {
            return "RABBITMQ".equalsIgnoreCase(name) ? client : null;
        }
    }

    /**
     * Records what reached the client, so tests can assert that rejected
     * requests never got that far.
     */
    private static final class RecordingClient implements MessageClient {

        private boolean pushCalled;
        private boolean pullCalled;
        private MessagePushCommand lastPush;
        private MessagePullCommand lastPull;
        private MessagePushResult pushResult;
        private MessagePullResult pullResult;
        private RuntimeException pushThrows;
        private RuntimeException pullThrows;

        @Override
        public MessagePushResult push(MessageConnectionParam param, MessagePushCommand command) {
            pushCalled = true;
            lastPush = command;
            if (pushThrows != null) {
                throw pushThrows;
            }
            if (pushResult != null) {
                return pushResult;
            }
            return MessagePushResult.success(
                    command.getExchange() == null ? "" : command.getExchange(),
                    command.getRoutingKey(),
                    16);
        }

        @Override
        public MessagePullResult pull(MessageConnectionParam param, MessagePullCommand command) {
            pullCalled = true;
            lastPull = command;
            if (pullThrows != null) {
                throw pullThrows;
            }
            if (pullResult != null) {
                return pullResult;
            }
            return MessagePullResult.of(List.of(), false, 1L);
        }
    }
}
