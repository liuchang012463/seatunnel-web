package org.apache.seatunnel.web.api.offlineimport;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Executes a split SQL script over one JDBC connection, statement by statement. */
public class SqlScriptImportRunner {

    /** Progress ticks; implementations persist throttled. */
    public interface ProgressCallback {
        void onUpdate(int executed, int failed, int total);
    }

    /** One statement head line, for logs. */
    private static final int HEAD_CHARS = 160;

    private final Connection connection;
    private final ExecutionLogBuffer log;
    private final int queryTimeoutSeconds;

    public SqlScriptImportRunner(Connection connection, ExecutionLogBuffer log) {
        this(connection, log, 0);
    }

    public SqlScriptImportRunner(Connection connection, ExecutionLogBuffer log, int queryTimeoutSeconds) {
        this.connection = connection;
        this.log = log;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /** Runs {@code prelude} (e.g. SET search_path) outside the statement counters. */
    public void runPrelude(List<String> preludeStatements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            applyTimeout(statement);
            for (String sql : preludeStatements) {
                log.line("执行会话预置语句: " + head(sql));
                statement.execute(sql);
            }
        }
    }

    /** Returns the failure message when the run aborted or statements failed, otherwise null. */
    public String execute(List<String> statements, boolean stopOnError, ProgressCallback callback)
            throws SQLException {
        int executed = 0;
        int failed = 0;
        try (Statement jdbc = connection.createStatement()) {
            applyTimeout(jdbc);
            for (int index = 0; index < statements.size(); index++) {
                String sql = statements.get(index);
                try {
                    if (CopyDataConverter.isCopyFromStdin(sql)) {
                        int rows = CopyDataConverter.execute(jdbc, sql);
                        executed++;
                        log.line("OK " + (index + 1) + "/" + statements.size()
                                + " COPY 数据块，写入 " + rows + " 行");
                    } else {
                        jdbc.execute(sql);
                        executed++;
                        log.line("OK " + (index + 1) + "/" + statements.size() + " " + head(sql));
                    }
                } catch (SQLException e) {
                    failed++;
                    String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    log.line("失败 " + (index + 1) + "/" + statements.size() + " " + head(sql) + " => " + reason);
                    if (stopOnError) {
                        callback.onUpdate(executed, failed, statements.size());
                        return "第 " + (index + 1) + " 条语句执行失败，已停止: "
                                + head(sql) + " => " + reason;
                    }
                }
                if ((index + 1) % 20 == 0 || index + 1 == statements.size()) {
                    callback.onUpdate(executed, failed, statements.size());
                }
            }
        }
        return failed > 0
                ? "共 " + failed + "/" + statements.size() + " 条语句执行失败，详见执行日志"
                : null;
    }

    private void applyTimeout(Statement statement) throws SQLException {
        if (queryTimeoutSeconds > 0) {
            statement.setQueryTimeout(queryTimeoutSeconds);
        }
    }

    private String head(String sql) {
        int index = 0;
        StringBuilder flat = new StringBuilder(HEAD_CHARS + 16);
        boolean lastSpace = false;
        while (index < sql.length() && flat.length() < HEAD_CHARS) {
            char c = sql.charAt(index++);
            if (Character.isWhitespace(c)) {
                if (!lastSpace && flat.length() > 0) {
                    flat.append(' ');
                    lastSpace = true;
                }
            } else {
                flat.append(c);
                lastSpace = false;
            }
        }
        String result = flat.toString().trim();
        return index < sql.length() ? result + "..." : result;
    }
}
