package org.apache.seatunnel.web.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.plugin.messaging.api.MessageClient;
import org.apache.seatunnel.plugin.messaging.api.MessageException;
import org.apache.seatunnel.plugin.messaging.api.MessagePullCommand;
import org.apache.seatunnel.plugin.messaging.api.MessagePullItem;
import org.apache.seatunnel.plugin.messaging.api.MessagePullResult;
import org.apache.seatunnel.plugin.messaging.api.MessagePushCommand;
import org.apache.seatunnel.plugin.messaging.api.MessagePushResult;
import org.apache.seatunnel.web.api.controller.message.MessagePullRequest;
import org.apache.seatunnel.web.api.controller.message.MessagePushRequest;
import org.apache.seatunnel.web.api.message.MessagePayloadValidator;
import org.apache.seatunnel.web.api.message.MessageProperties;
import org.apache.seatunnel.web.api.message.plugin.MessagePluginManager;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic message push/pull endpoints for external integrations.
 *
 * <p>
 * Two operations only, both request/response:
 * </p>
 * <ul>
 *   <li>{@code POST /api/v1/message/push} — publish one JSON payload</li>
 *   <li>{@code POST /api/v1/message/pull} — retrieve up to N JSON payloads</li>
 * </ul>
 *
 * <h3>Error convention</h3>
 * <p>
 * Every application-level failure returns HTTP 200 with a non-zero {@code code}
 * in the {@link Result} envelope. These endpoints do not require login or an
 * API key.
 * </p>
 * <p>
 * This is deliberate (design decision C2): generic HTTP clients retry 5xx
 * responses automatically, and retrying a malformed payload is pointless and,
 * for a push, actively harmful. Keeping failures inside the envelope also means
 * integrators only need one parsing path.
 * </p>
 *
 * <h3>Reliability — read before reporting bugs</h3>
 * <p>
 * Push does not await publisher confirms and pull auto-acks. A message can
 * therefore be lost between broker and caller (at-most-once). See
 * {@code docs/rabbitmq-messaging-implementation.md} §9.
 * </p>
 */
@RestController
@Tag(name = "MESSAGE_TAG", description = "通用消息推送 / 拉取接口")
@RequestMapping("/api/v1/message")
@Slf4j
public class MessageController {

    private static final int DEFAULT_MAX_MESSAGES = 10;

    @Resource
    private MessagePluginManager messagePluginManager;

    @Resource
    private MessageProperties messageProperties;

    @Resource
    private MessagePayloadValidator payloadValidator;

    // -------------------- push --------------------

    @PostMapping("/push")
    @Operation(summary = "pushMessage",
            description = "Publish one JSON message to the configured broker. 尽力而为，不保证不丢。")
    public ResponseEntity<Result<Map<String, Object>>> push(@RequestBody MessagePushRequest request) {
        MessageClient client = resolveClient(request.resolveClientType());
        if (client == null) {
            return ok(Result.buildFailure("不支持的消息类型: " + request.resolveClientType()));
        }

        // Size and JSON checks run before any broker work (decision D2).
        String rejection = payloadValidator.validate(request.getMessage());
        if (rejection != null) {
            return ok(Result.buildFailure(rejection));
        }

        MessagePushCommand command = MessagePushCommand.builder()
                .exchange(request.getExchange())
                .queue(request.getQueue())
                .routingKey(request.getRoutingKey())
                .payload(request.getMessage())
                .persistent(request.getPersistent() == null || request.getPersistent())
                .headers(request.getHeaders())
                .build();

        MessagePushResult result;
        try {
            result = client.push(messageProperties.toConnectionParam(), command);
        } catch (RuntimeException e) {
            logFailure("消息推送", "clientType=" + request.resolveClientType(), e);
            return ok(Result.buildFailure(sanitize(e)));
        }

        if (!result.isSuccess()) {
            return ok(Result.buildFailure(result.getMessage()));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", true);
        data.put("exchange", result.getExchange());
        data.put("routingKey", result.getRoutingKey());
        data.put("bodyBytes", result.getBodyBytes());
        return ok(Result.buildSuc(data));
    }

    // -------------------- pull --------------------

    @PostMapping("/pull")
    @Operation(summary = "pullMessages",
            description = "Retrieve up to maxMessages JSON messages. AUTO ack，取走即出队。")
    public ResponseEntity<Result<Map<String, Object>>> pull(@RequestBody MessagePullRequest request) {
        MessageClient client = resolveClient(request.resolveClientType());
        if (client == null) {
            return ok(Result.buildFailure("不支持的消息类型: " + request.resolveClientType()));
        }

        int requested = request.getMaxMessages() == null ? DEFAULT_MAX_MESSAGES : request.getMaxMessages();
        if (requested <= 0) {
            return ok(Result.buildFailure("maxMessages 必须大于 0"));
        }
        // Server-side hard ceiling, independent of what the caller asked for.
        int limit = Math.min(requested, messageProperties.getMaxBatchSize());

        long timeoutMs = request.getTimeoutMs() == null
                ? messageProperties.getPullDefaultTimeoutMs()
                : request.getTimeoutMs();

        MessagePullCommand command = MessagePullCommand.builder()
                .queue(request.getQueue())
                .maxMessages(limit)
                .timeoutMs(timeoutMs)
                .build();

        MessagePullResult result;
        try {
            result = client.pull(messageProperties.toConnectionParam(), command);
        } catch (RuntimeException e) {
            logFailure("消息拉取",
                    "clientType=" + request.resolveClientType() + ", queue=" + request.getQueue(), e);
            return ok(Result.buildFailure(sanitize(e)));
        }

        if (!result.isSuccess()) {
            return ok(Result.buildFailure(result.getMessage()));
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        for (MessagePullItem item : result.getMessages()) {
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("deliveryTag", item.getDeliveryTag());
            vo.put("parseable", item.isParseable());
            if (item.isParseable()) {
                vo.put("body", item.getBody());
            } else {
                vo.put("rawBody", item.getRawBody());
            }
            if (item.getHeaders() != null) {
                vo.put("headers", item.getHeaders());
            }
            if (item.getContentType() != null) {
                vo.put("contentType", item.getContentType());
            }
            messages.add(vo);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messages", messages);
        data.put("returned", result.getReturned());
        data.put("truncated", result.isTruncated());
        data.put("elapsedMs", result.getElapsedMs());
        return ok(Result.buildSuc(data));
    }

    // -------------------- internal --------------------

    private MessageClient resolveClient(String clientType) {
        return messagePluginManager.getClient(clientType);
    }

    private ResponseEntity<Result<Map<String, Object>>> ok(Result<Map<String, Object>> body) {
        return ResponseEntity.ok(body);
    }

    /**
     * Log a failed push/pull at a level of detail that matches who can act on it.
     *
     * <p>
     * {@link MessageException} is the contract's channel for caller-fixable
     * defects, and its message is required to be sanitized (see its javadoc), so
     * the message is the whole story — a stack trace adds nothing. That matters
     * at this call site: a single misconfigured caller retrying in a loop would
     * otherwise emit a ~60-line trace per request and bury genuine faults.
     * </p>
     *
     * <p>
     * Anything else reaching this point is a defect in this service or in a
     * plugin, and there the trace is the only useful artefact.
     * </p>
     */
    private void logFailure(String action, String context, RuntimeException e) {
        if (isCallerFixable(e)) {
            log.warn("{}失败, {}, reason={}", action, context, e.getMessage());
        } else {
            log.warn("{}失败, {}", action, context, e);
        }
    }

    /**
     * Mirrors {@code RabbitMessageClient#isCallerFixable}: a bare
     * {@link MessageException} is a caller mistake; one wrapping a cause is a
     * transport failure whose detail lives in the cause.
     */
    private boolean isCallerFixable(RuntimeException e) {
        return e instanceof MessageException && e.getCause() == null;
    }

    /**
     * Reduce an exception to text that reveals no internal topology.
     *
     * <p>Anything not already sanitized by the plugin falls back to a generic
     * message; the actionable detail stays in the server log.</p>
     */
    private String sanitize(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "消息处理失败";
        }
        // Plugin-level messages are authored to be caller-safe; other runtimes
        // (NPE, class-cast, ...) are not.
        if (e.getClass().getName().startsWith("org.apache.seatunnel.plugin.messaging")) {
            return message;
        }
        return "消息处理失败";
    }
}
