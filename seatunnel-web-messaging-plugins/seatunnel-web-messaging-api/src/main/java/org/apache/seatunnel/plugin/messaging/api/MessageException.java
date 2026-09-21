package org.apache.seatunnel.plugin.messaging.api;

/**
 * Thrown for programming errors inside a messaging plugin — null arguments,
 * unsupported option combinations, and similar defects.
 *
 * <p>
 * <b>Not</b> the channel for expected operational failures. An unreachable
 * broker, bad credentials or a missing queue are normal outcomes that
 * implementations report through {@link MessagePushResult#fail} /
 * {@link MessagePullResult#fail}, because the API layer must map them to
 * sanitized responses rather than stack traces.
 * </p>
 *
 * <p>
 * Messages on this exception must already be sanitized: no host names, no
 * credentials, no internal topology (design decision C2). Full detail belongs in
 * the server log.
 * </p>
 */
public class MessageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MessageException(String message) {
        super(message);
    }

    public MessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
