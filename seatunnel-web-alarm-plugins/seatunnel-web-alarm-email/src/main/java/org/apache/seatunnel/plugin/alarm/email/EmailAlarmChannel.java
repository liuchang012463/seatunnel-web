package org.apache.seatunnel.plugin.alarm.email;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.plugin.alarm.api.AlarmChannel;
import org.apache.seatunnel.plugin.alarm.api.AlarmData;
import org.apache.seatunnel.plugin.alarm.api.AlarmInfo;
import org.apache.seatunnel.plugin.alarm.api.AlarmResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

/**
 * SMTP email alarm channel. The channel is stateless; SMTP settings are
 * supplied by the alarm channel instance configuration for each delivery.
 */
@Slf4j
public class EmailAlarmChannel implements AlarmChannel {

    public static final String CHANNEL_TYPE = EmailAlarmChannelFactory.EMAIL;

    private static final int DEFAULT_PORT = 587;
    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final String DEFAULT_SUBJECT = "[SeaTunnel] ${title}";
    private static final String DEFAULT_BODY =
            "告警标题：${title}\n严重级别：${severity}\n\n${content}\n\n日志：\n${log}";

    @Override
    public AlarmResult process(AlarmInfo info) {
        Map<String, String> params = info == null ? null : info.getAlarmParams();
        if (params == null || params.isEmpty()) {
            return AlarmResult.fail("email alarm params is empty");
        }

        AlarmData data = info.getAlarmData();
        if (data == null) {
            return AlarmResult.fail("email alarm data is empty");
        }

        String host = trim(params.get("host"));
        String to = trim(params.get("to"));
        if (host.isEmpty()) {
            return AlarmResult.fail("SMTP host is not configured");
        }
        if (to.isEmpty()) {
            return AlarmResult.fail("email recipient is not configured");
        }

        int port = parseIntOrDefault(params.get("port"), DEFAULT_PORT);
        int timeoutMs = parseIntOrDefault(params.get("timeoutMs"), DEFAULT_TIMEOUT_MS);
        String username = trim(params.get("username"));
        String password = params.getOrDefault("password", "");
        String from = firstNonBlank(params.get("from"), username);
        if (from.isEmpty()) {
            return AlarmResult.fail("email sender is not configured");
        }

        Properties properties = buildProperties(params, host, port, timeoutMs, username);
        String subject = renderTemplate(params.getOrDefault("subjectTemplate", DEFAULT_SUBJECT), data);
        String body = renderTemplate(params.getOrDefault("bodyTemplate", DEFAULT_BODY), data);
        String contentType = "text/html".equalsIgnoreCase(trim(params.get("contentType")))
                ? "text/html"
                : "text/plain";

        try {
            Session session = createSession(properties, username, password);
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from, true));
            message.setRecipients(
                    Message.RecipientType.TO, InternetAddress.parse(to.replace(';', ','), true));
            message.setSubject(subject, StandardCharsets.UTF_8.name());
            if ("text/html".equals(contentType)) {
                message.setContent(body, "text/html; charset=UTF-8");
            } else {
                message.setText(body, StandardCharsets.UTF_8.name());
            }

            Transport.send(message);
            return AlarmResult.success("email sent");
        } catch (MessagingException | IllegalArgumentException e) {
            log.warn("Email alarm delivery failed, host={}, port={}", host, port, e);
            return AlarmResult.fail(messageOf(e));
        }
    }

    private Properties buildProperties(
            Map<String, String> params,
            String host,
            int port,
            int timeoutMs,
            String username) {
        Properties properties = new Properties();
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", String.valueOf(port));
        properties.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMs));
        properties.put("mail.smtp.timeout", String.valueOf(timeoutMs));
        properties.put("mail.smtp.writetimeout", String.valueOf(timeoutMs));
        properties.put("mail.smtp.auth", String.valueOf(!username.isEmpty()));
        properties.put("mail.smtp.starttls.enable", String.valueOf(parseBoolean(params.get("starttls"), true)));
        properties.put("mail.smtp.ssl.enable", String.valueOf(parseBoolean(params.get("ssl"), false)));
        return properties;
    }

    private Session createSession(Properties properties, String username, String password) {
        if (username.isEmpty()) {
            return Session.getInstance(properties);
        }

        return Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });
    }

    private String renderTemplate(String template, AlarmData data) {
        String result = template == null ? "" : template;
        result = result.replace("${title}", safe(data.getTitle()));
        result = result.replace("${content}", safe(data.getContent()));
        result = result.replace(
                "${severity}",
                data.getSeverity() == null ? "" : data.getSeverity().name());
        result = result.replace("${log}", safe(data.getLog()));
        return result;
    }

    private String firstNonBlank(String first, String fallback) {
        String primary = trim(first);
        return primary.isEmpty() ? trim(fallback) : primary;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private int parseIntOrDefault(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(trim(value));
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean parseBoolean(String value, boolean fallback) {
        String normalized = trim(value);
        if (normalized.isEmpty()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(normalized)
                || "1".equals(normalized)
                || "yes".equalsIgnoreCase(normalized);
    }

    private String messageOf(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? "email delivery failed"
                : exception.getMessage();
    }
}
