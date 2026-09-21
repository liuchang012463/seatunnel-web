package org.apache.seatunnel.plugin.messaging.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the credential masking on {@link MessageConnectionParam#toString()}.
 *
 * <p>
 * This is a real safety property, not cosmetics: the parameter object is passed
 * to the broker client, whose failure paths log it, and it can surface in
 * framework exception text. A regression here would leak the caller's password
 * into the server log.
 * </p>
 */
class MessageConnectionParamTest {

    private static final String SECRET = "sup3r-s3cret-pw";

    private MessageConnectionParam sample() {
        MessageConnectionParam param = new MessageConnectionParam();
        param.setHost("mq.internal.example");
        param.setPort(5672);
        param.setVirtualHost("/prod");
        param.setUsername("order-svc");
        param.setPassword(SECRET);
        return param;
    }

    @Test
    void toStringMustNotContainPassword() {
        String rendered = sample().toString();
        assertFalse(rendered.contains(SECRET),
                "toString() 泄露了明文密码 —— 该对象会进入日志与异常信息");
        assertFalse(rendered.toLowerCase().contains("sup3r"),
                "toString() 泄露了密码片段");
        assertTrue(rendered.contains("******"), "应保留掩码占位，便于确认字段确实存在");
    }

    @Test
    void toStringKeepsDiagnosticFields() {
        String rendered = sample().toString();
        assertTrue(rendered.contains("mq.internal.example"), "主机应保留，用于排查");
        assertTrue(rendered.contains("order-svc"), "用户名应保留，用于排查");
        assertTrue(rendered.contains("5672"), "端口应保留，用于排查");
    }

    @Test
    void shouldApplyDefaultsWhenUnset() {
        MessageConnectionParam param = new MessageConnectionParam();
        param.setHost("h");
        param.setUsername("u");
        param.setPassword("p");

        assertEquals(5672, param.resolvePort(), "端口默认 5672");
        assertEquals("/", param.resolveVirtualHost(), "vhost 默认 /");
        assertEquals(10_000, param.resolveConnectionTimeoutMs(), "连接超时默认 10s");
        assertFalse(param.isSslEnabled(), "TLS 默认关闭");
    }

    @Test
    void shouldTreatNullAndInvalidAsUnset() {
        MessageConnectionParam param = new MessageConnectionParam();
        param.setPort(null);
        param.setVirtualHost("  ");
        param.setConnectionTimeoutMs(0);
        param.setSslEnabled(null);

        assertEquals(5672, param.resolvePort(), "null 端口应回退默认值");
        assertEquals("/", param.resolveVirtualHost(), "空白 vhost 应回退默认值");
        assertEquals(10_000, param.resolveConnectionTimeoutMs(), "0 超时应回退默认值");
        assertFalse(param.isSslEnabled(), "null 开关应按 false 处理，避免拆箱 NPE");
    }

    @Test
    void shouldHonourExplicitValues() {
        MessageConnectionParam param = new MessageConnectionParam();
        param.setPort(5671);
        param.setVirtualHost("/staging");
        param.setConnectionTimeoutMs(1500);
        param.setSslEnabled(Boolean.TRUE);

        assertEquals(5671, param.resolvePort());
        assertEquals("/staging", param.resolveVirtualHost());
        assertEquals(1500, param.resolveConnectionTimeoutMs());
        assertTrue(param.isSslEnabled());
    }

    @Test
    void toStringMustNotThrowWhenFieldsAreNull() {
        // toString() is called from logging paths, so it must never be the thing
        // that throws and masks the original failure.
        MessageConnectionParam param = new MessageConnectionParam();
        String rendered = param.toString();
        assertTrue(rendered.contains("MessageConnectionParam"));
    }
}
