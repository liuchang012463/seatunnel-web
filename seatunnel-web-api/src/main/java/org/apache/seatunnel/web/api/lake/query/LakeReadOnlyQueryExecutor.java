package org.apache.seatunnel.web.api.lake.query;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Executes only a normalized structured plan against a caller-supplied
 * read-only DataSource.  The DataSource itself is intentionally injected so
 * production wiring can provide a dedicated pool while tests can use a fake.
 */
public final class LakeReadOnlyQueryExecutor {

    private final DataSource dataSource;
    private final LakeReadOnlyQueryProperties properties;
    private final LakeReadOnlyQuerySqlGenerator sqlGenerator;

    public LakeReadOnlyQueryExecutor(
            DataSource dataSource,
            LakeReadOnlyQueryProperties properties) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.properties = Objects.requireNonNull(properties, "properties");
        validateProperties(properties);
        this.sqlGenerator = new LakeReadOnlyQuerySqlGenerator(
                boundedInt(properties.getMaxRows(), LakeQueryErrorCode.RESULT_LIMIT_INVALID));
    }

    public LakeReadOnlyQueryExecutor(DataSource dataSource) {
        this(dataSource, new LakeReadOnlyQueryProperties());
    }

    /** Executes a normalized plan; no raw SQL entry point exists here. */
    public LakeReadOnlyQueryResultVO execute(LakeReadOnlyQueryPlan plan) {
        if (plan == null) {
            throw new LakeQueryExecutionException(LakeQueryErrorCode.READONLY_REJECTED);
        }
        final String generatedSql;
        try {
            generatedSql = sqlGenerator.generate(plan);
        } catch (RuntimeException exception) {
            if (exception instanceof LakeQueryExecutionException queryException) {
                throw queryException;
            }
            throw new LakeQueryExecutionException(LakeQueryErrorCode.READONLY_REJECTED);
        }
        if (!isReadOnlyStatement(generatedSql)) {
            throw new LakeQueryExecutionException(LakeQueryErrorCode.READONLY_REJECTED);
        }

        long started = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            setReadOnlyBestEffort(connection);
            try (PreparedStatement statement = connection.prepareStatement(generatedSql)) {
                try {
                    configure(statement, plan);
                    return read(statement, plan, started);
                } catch (SQLException exception) {
                    // A timeout or driver-side failure may leave the
                    // statement running; cancel before closing it.
                    cancelBestEffort(statement);
                    throw classify(exception, started);
                }
            }
        } catch (LakeQueryExecutionException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new LakeQueryExecutionException(
                    LakeQueryErrorCode.DATASOURCE_UNAVAILABLE);
        } catch (RuntimeException exception) {
            if (exception instanceof LakeQueryExecutionException queryException) {
                throw queryException;
            }
            throw new LakeQueryExecutionException(LakeQueryErrorCode.EXECUTION_FAILED);
        }
    }

    private LakeReadOnlyQueryResultVO read(
            PreparedStatement statement,
            LakeReadOnlyQueryPlan plan,
            long started) throws SQLException {
        long maxBytes = properties.getMaxBytes();
        int maxRows = Math.min(plan.effectiveLimit(),
                boundedInt(properties.getMaxRows(), LakeQueryErrorCode.RESULT_LIMIT_INVALID));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> columns;
        long bytes = 0;
        boolean truncated = plan.requestedLimit() > maxRows;
        try (ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metadata = resultSet.getMetaData();
            int columnCount = metadata.getColumnCount();
            columns = columnLabels(metadata, columnCount);
            while (rows.size() < maxRows && resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                long rowBytes = 0;
                for (int index = 1; index <= columnCount; index++) {
                    Object value = resultSet.getObject(index);
                    row.put(columns.get(index - 1), value);
                    rowBytes = saturatedAdd(rowBytes, estimateBytes(value));
                }
                if (rowBytes > maxBytes - bytes) {
                    cancelBestEffort(statement);
                    truncated = true;
                    break;
                }
                rows.add(row);
                bytes = saturatedAdd(bytes, rowBytes);
            }
            if (rows.size() == maxRows && maxRows < plan.effectiveLimit()) {
                cancelBestEffort(statement);
                truncated = true;
            }
        }
        return new LakeReadOnlyQueryResultVO(columns, rows, rows.size(), bytes, truncated,
                elapsedMillis(started), plan.explain());
    }

    private void configure(PreparedStatement statement, LakeReadOnlyQueryPlan plan)
            throws SQLException {
        statement.setQueryTimeout(timeoutSeconds(properties.getQueryTimeout()));
        int maxRows = Math.min(plan.effectiveLimit(),
                boundedInt(properties.getMaxRows(), LakeQueryErrorCode.RESULT_LIMIT_INVALID));
        if (maxRows <= 0) {
            throw new LakeQueryExecutionException(LakeQueryErrorCode.RESULT_LIMIT_INVALID);
        }
        statement.setMaxRows(maxRows);
    }

    private LakeQueryExecutionException classify(SQLException exception, long started) {
        if (isCancelled(exception)) {
            return new LakeQueryExecutionException(LakeQueryErrorCode.CANCELLED);
        }
        if (isTimeout(exception) || elapsedMillis(started) >= timeoutMillis(properties.getQueryTimeout())) {
            return new LakeQueryExecutionException(LakeQueryErrorCode.TIMEOUT);
        }
        return new LakeQueryExecutionException(LakeQueryErrorCode.EXECUTION_FAILED);
    }

    private static List<String> columnLabels(ResultSetMetaData metadata, int count)
            throws SQLException {
        List<String> labels = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            String label = metadata.getColumnLabel(index);
            if (label == null || label.isBlank()) {
                label = "column" + index;
            }
            if (labels.contains(label)) {
                label = label + "_" + index;
            }
            labels.add(label);
        }
        return labels;
    }

    private static long estimateBytes(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof byte[] bytes) {
            return bytes.length;
        }
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static long saturatedAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static boolean isReadOnlyStatement(String sql) {
        String normalized = sql.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("SELECT ") || normalized.startsWith("EXPLAIN SELECT ");
    }

    private static void setReadOnlyBestEffort(Connection connection) {
        try {
            connection.setReadOnly(true);
        } catch (SQLException ignored) {
            // Some JDBC drivers reject the hint. The generated statement
            // boundary remains mandatory and is checked independently.
        }
    }

    private static void cancelBestEffort(PreparedStatement statement) {
        if (statement == null) {
            return;
        }
        try {
            statement.cancel();
        } catch (SQLException ignored) {
            // Closing the statement in try-with-resources is the final guard.
        }
    }

    private static boolean isTimeout(SQLException exception) {
        String state = exception.getSQLState();
        return "57014".equals(state) || "HYT00".equals(state) || "HYT01".equals(state);
    }

    private static boolean isCancelled(SQLException exception) {
        return "57014".equals(exception.getSQLState());
    }

    private static int timeoutSeconds(Duration timeout) {
        long millis = timeoutMillis(timeout);
        long seconds = millis / 1_000;
        if (millis % 1_000 != 0 && seconds < Long.MAX_VALUE) {
            seconds++;
        }
        seconds = Math.max(1, seconds);
        return (int) Math.min(Integer.MAX_VALUE, seconds);
    }

    private static long timeoutMillis(Duration timeout) {
        try {
            return Math.max(1, timeout.toMillis());
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static int boundedInt(long value, String errorCode) {
        if (value <= 0) {
            throw new LakeQueryExecutionException(errorCode);
        }
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    private static void validateProperties(LakeReadOnlyQueryProperties properties) {
        if (properties.getQueryTimeout() == null || timeoutMillis(properties.getQueryTimeout()) <= 0) {
            throw new LakeQueryExecutionException(LakeQueryErrorCode.CONFIG_INVALID);
        }
        if (properties.getMaxRows() <= 0) {
            throw new LakeQueryExecutionException(LakeQueryErrorCode.RESULT_LIMIT_INVALID);
        }
        if (properties.getMaxBytes() <= 0) {
            throw new LakeQueryExecutionException(LakeQueryErrorCode.RESULT_BYTES_INVALID);
        }
    }
}
