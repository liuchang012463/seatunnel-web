package org.apache.seatunnel.web.api.lake.query;

import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-local registry for cancelling an in-flight bounded read-only query.
 *
 * <p>The query itself remains synchronous for compatibility with the existing
 * API.  A caller supplies an opaque query id, then may issue a cancellation
 * request from another browser request while the original request is still
 * running.  The registry never stores SQL, rows, credentials or connection
 * objects after the query finishes.</p>
 */
@Component
public final class LakeReadOnlyQueryCancellationRegistry {

    private static final int MAX_QUERY_ID_LENGTH = 128;
    private static final long PENDING_TTL_MILLIS = 5 * 60 * 1_000L;
    private static final int MAX_PENDING_QUERY_IDS = 1_024;

    private final ConcurrentMap<String, Handle> active = new ConcurrentHashMap<>();
    /**
     * A cancel request can arrive while the query is still reading the
     * server-side allowlist.  Keep that intent briefly so registration does
     * not lose a click made immediately after the browser submitted a query.
     */
    private final ConcurrentMap<String, Long> pending = new ConcurrentHashMap<>();

    /** Returns a generated id when the caller did not supply one. */
    public String normalizeOrGenerate(String queryId) {
        if (queryId == null || queryId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String value = queryId.trim();
        if (value.length() > MAX_QUERY_ID_LENGTH
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new LakeQueryExecutionException(LakeQueryErrorCode.CONFIG_INVALID);
        }
        return value;
    }

    public Registration register(String queryId) {
        String id = Objects.requireNonNull(queryId, "queryId");
        Handle handle = new Handle();
        purgeExpiredPending();
        if (pending.remove(id) != null) {
            handle.cancelled.set(true);
        }
        if (active.putIfAbsent(id, handle) != null) {
            throw new LakeQueryExecutionException(LakeQueryErrorCode.CONFIG_INVALID);
        }
        return new Registration(id, handle);
    }

    /**
     * Requests cancellation.  A valid id is retained briefly when the query
     * has not reached JDBC registration yet; this closes the metadata-loading
     * race without retaining SQL, rows or credentials.
     */
    public boolean cancel(String queryId) {
        if (queryId == null || queryId.isBlank()) {
            return false;
        }
        final String id;
        try {
            id = normalizeOrGenerate(queryId);
        } catch (RuntimeException exception) {
            return false;
        }
        Handle handle = active.get(id);
        if (handle != null) {
            return handle.cancel();
        }
        purgeExpiredPending();
        // Unknown ids are accepted only for the short metadata-loading race;
        // cap that buffer so an unauthenticated stream of random ids cannot
        // grow process memory without bound.
        if (pending.size() >= MAX_PENDING_QUERY_IDS && !pending.containsKey(id)) {
            return false;
        }
        pending.put(id, System.currentTimeMillis());
        return true;
    }

    public boolean isActive(String queryId) {
        return queryId != null && active.containsKey(queryId);
    }

    public final class Registration implements AutoCloseable {

        private final String queryId;
        private final Handle handle;

        private Registration(String queryId, Handle handle) {
            this.queryId = queryId;
            this.handle = handle;
        }

        public String queryId() {
            return queryId;
        }

        public boolean cancelled() {
            return handle.cancelled.get();
        }

        public void attach(Statement statement) {
            handle.statement.set(statement);
            if (handle.cancelled.get()) {
                handle.cancelStatement();
            }
        }

        @Override
        public void close() {
            active.remove(queryId, handle);
            handle.statement.set(null);
            pending.remove(queryId);
        }
    }

    private void purgeExpiredPending() {
        long cutoff = System.currentTimeMillis() - PENDING_TTL_MILLIS;
        pending.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() < cutoff);
    }

    private static final class Handle {

        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Statement> statement = new AtomicReference<>();

        private boolean cancel() {
            cancelled.set(true);
            cancelStatement();
            return true;
        }

        private void cancelStatement() {
            Statement current = statement.get();
            if (current == null) {
                return;
            }
            try {
                current.cancel();
            } catch (SQLException ignored) {
                // Closing the statement in the executor remains the final guard.
            }
        }
    }
}
