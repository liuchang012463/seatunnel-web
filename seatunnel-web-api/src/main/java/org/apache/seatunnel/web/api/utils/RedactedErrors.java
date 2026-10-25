package org.apache.seatunnel.web.api.utils;

import org.apache.commons.lang3.StringUtils;

/**
 * Keeps the credentials of the Engine out of the logs and of the per job log files.
 *
 * <p>
 * The Engine echoes the connector options of a failed job in its error message, and the cause
 * chain of the resulting exception carries the raw response body of the Engine. A throwable that
 * is handed to the logger prints that text, so the logger only ever receives the masked message
 * and the stack frames of the failure.
 * </p>
 */
public final class RedactedErrors {

    private RedactedErrors() {
    }

    /**
     * The error text with the sensitive parts removed. A blank text stays blank.
     */
    public static String mask(String message) {
        if (StringUtils.isBlank(message)) {
            return message;
        }
        return HoconSensitiveMaskUtil.maskSensitiveText(message);
    }

    /**
     * A throwable that carries the masked message and the stack frames of the original failure
     * but neither its message nor its cause chain, so the Engine text never reaches the log.
     *
     * @param error         the original failure
     * @param maskedMessage the masked message of the failure
     * @return the throwable to log, or {@code null} when there is no failure
     */
    public static Throwable redacted(Throwable error, String maskedMessage) {
        if (error == null) {
            return null;
        }
        RuntimeException wrapper = new RuntimeException(maskedMessage);
        wrapper.setStackTrace(error.getStackTrace());
        return wrapper;
    }

    /**
     * The type of the failure, for a log line that must name it without printing the original
     * throwable.
     */
    public static String typeOf(Throwable error) {
        return error == null ? null : error.getClass().getName();
    }
}
