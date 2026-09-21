package org.apache.seatunnel.web.api.message;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Validates and serializes caller-supplied message payloads.
 *
 * <p>
 * Pulled out of {@code MessageController} so the size cap and the parse
 * constraints can be unit tested directly. Both are security-relevant: the
 * payload structure is caller-defined, so nothing about it can be trusted.
 * </p>
 *
 * <h3>Two independent protections</h3>
 * <ol>
 *   <li><b>Parse constraints</b> — a dedicated {@link ObjectMapper} caps nesting
 *       depth, string length and number length below Jackson's defaults, so a
 *       deeply nested or oversized token is rejected while parsing rather than
 *       consuming unbounded CPU and memory.</li>
 *   <li><b>Serialized size cap</b> — the payload is re-serialized and measured
 *       against {@code seatunnel.message.max-body-bytes} (default 1MB) before
 *       any broker work happens.</li>
 * </ol>
 *
 * <p>
 * The size check measures the <b>re-serialized</b> form rather than the raw
 * request bytes, because the former is what actually reaches the broker. A
 * compactly written request can still expand during canonicalization, so
 * measuring the wire input would under-count.
 * </p>
 */
@Component
@Slf4j
public class MessagePayloadValidator {

    private final MessageProperties properties;

    private final ObjectMapper objectMapper;

    public MessagePayloadValidator(MessageProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper(JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(properties.getMaxNestingDepth())
                        .maxStringLength(properties.getMaxStringLength())
                        .maxNumberLength(properties.getMaxNumberLength())
                        .build())
                .build());
    }

    /**
     * Check a payload against the size cap.
     *
     * @param payload the caller-supplied tree; may be {@code null}
     * @return {@code null} when the payload is acceptable, otherwise a
     *         caller-facing rejection reason. The reason is already safe to
     *         return: it names a limit and an actual size, never internal
     *         detail.
     */
    public String validate(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return "message 不能为空";
        }

        byte[] serialized;
        try {
            serialized = objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            log.warn("消息体序列化失败", e);
            return "消息体不是合法的 JSON";
        }

        int limit = properties.getMaxBodyBytes();
        if (serialized.length > limit) {
            return "消息体超过 " + describeBytes(limit) + " 限制，实际 "
                    + describeBytes(serialized.length);
        }
        return null;
    }

    /**
     * Serialize an already-validated payload for the broker.
     *
     * @throws IllegalArgumentException when the payload cannot be serialized;
     *         callers are expected to have run {@link #validate} first
     */
    public byte[] serialize(JsonNode payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("消息体不是合法的 JSON", e);
        }
    }

    /**
     * Render a byte count in whole MB when it divides evenly, else in KB.
     *
     * <p>A raw {@code 1048576 / 1024} reads as "1024KB" where an operator
     * expects "1MB", and the confusing number ends up in an error message shown
     * to integrators.</p>
     */
    private String describeBytes(int bytes) {
        if (bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0) {
            return (bytes / (1024 * 1024)) + "MB";
        }
        return (bytes / 1024) + "KB";
    }
}
