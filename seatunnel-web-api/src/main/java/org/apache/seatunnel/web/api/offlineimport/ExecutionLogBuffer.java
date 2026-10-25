package org.apache.seatunnel.web.api.offlineimport;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Bounded execution log kept alongside one offline import run. */
public class ExecutionLogBuffer {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final int MAX_LINE_CHARS = 500;

    private final StringBuilder buffer = new StringBuilder();
    private final int maxChars;

    public ExecutionLogBuffer(int maxChars) {
        this.maxChars = maxChars;
    }

    public synchronized void line(String text) {
        append("[" + LocalDateTime.now().format(TS) + "] " + normalize(text));
    }

    public synchronized void raw(String text) {
        append(normalize(text));
    }

    private void append(String line) {
        if (buffer.length() > 0) {
            buffer.append('\n');
        }
        buffer.append(line);
        if (buffer.length() > maxChars) {
            buffer.delete(0, buffer.length() - maxChars);
            int nextNewline = buffer.indexOf("\n");
            if (nextNewline >= 0) {
                buffer.delete(0, nextNewline + 1);
            }
        }
    }

    private String normalize(String text) {
        String flat = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        if (flat.length() <= MAX_LINE_CHARS) {
            return flat;
        }
        return flat.substring(0, MAX_LINE_CHARS) + "...(截断)";
    }

    public synchronized String snapshot() {
        return buffer.toString();
    }
}
