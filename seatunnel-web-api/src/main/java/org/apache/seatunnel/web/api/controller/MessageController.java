package org.apache.seatunnel.web.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.plugin.messaging.api.MessageClient;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
 * in the {@link Result} envelope. The only exception is authentication, which
 * returns a real HTTP 401.
 * </p>
 * <p>
 * This is deliberate (design decision C2): generic HTTP clients retry 5xx
 * responses automatically, and retrying an auth failure or a malformed payload
 * is pointless and, for a push, actively harmful. Keeping failures inside the
 * envelope also means integrators only need one parsing path.
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

    private static final String API_KEY_HEADER = "X-Api-Key";

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
            description = "Publish one JSON message to the caller's broker. 尽力而为，不保证不丢。")
    public ResponseEntity<Result<Map<String, Object>>> push(@RequestBody MessagePushRequest request,
                                                            HttpServletRequest httpRequest) {
        assertApiKey(httpRequest);

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
            result = client.push(request.getConnection(), command);
        } catch (RuntimeException e) {
            // Full detail to the server log; the caller gets a sanitized message.
            log.warn("消息推送失败, clientType={}", request.resolveClientType(), e);
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
    public ResponseEntity<Result<Map<String, Object>>> pull(@RequestBody MessagePullRequest request,
                                                            HttpServletRequest httpRequest) {
        assertApiKey(httpRequest);

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
            result = client.pull(request.getConnection(), command);
        } catch (RuntimeException e) {
            log.warn("消息拉取失败, clientType={}, queue={}",
                    request.resolveClientType(), request.getQueue(), e);
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

    /**
     * Enforce the shared API key when one is configured.
     *
     * <p>
     * Implemented here rather than as a filter or interceptor so that nothing
     * outside this endpoint family is affected (design decision F1) — no change
     * to {@code WebMvcConfig}, and existing routes keep their current
     * behaviour.
     * </p>
     *
     * <p>
     * The comparison is not constant-time. That is an accepted limitation for a
     * shared static key on a best-effort relay; a timing side channel against a
     * 48-hex-char secret over a network is not the weak link here.
     * </p>
     */
    private void assertApiKey(HttpServletRequest request) {
        if (!messageProperties.isAuthEnabled()) {
            return;
        }
        String provided = request.getHeader(API_KEY_HEADER);
        if (!messageProperties.getApiKey().equals(provided)) {
            log.warn("消息接口鉴权失败, remoteAddr={}, uri={}",
                    request.getRemoteAddr(), request.getRequestURI());
            throw new UnauthorizedException();
        }
    }

    private MessageClient resolveClient(String clientType) {
        return messagePluginManager.getClient(clientType);
    }

    private ResponseEntity<Result<Map<String, Object>>> ok(Result<Map<String, Object>> body) {
        return ResponseEntity.ok(body);
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

    /**
     * Signals a failed API-key check.
     *
     * <p>Mapped to HTTP 401 by {@link #handleUnauthorized()} rather than
     * flowing through the global handler, which would turn it into a 200 with a
     * generic code.</p>
     */
    private static class UnauthorizedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * Convert the auth failure into a real 401.
     *
     * <p>Kept local to this controller so the global exception handling used by
     * every other endpoint is untouched.</p>
     *
     * <p>The shared {@code Status} enum has no auth entry, so the numeric code
     * is written explicitly and paired with plain text. The HTTP status is what
     * integrators branch on.</p>
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Result<Void>> handleUnauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.buildFailure(401, "鉴权失败"));
    }
}
