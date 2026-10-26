package org.apache.seatunnel.web.api.offlineimport;

import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Executes a preflighted SQL dump over one JDBC connection. */
public class SqlScriptImportRunner {

    /** Progress ticks; implementations persist throttled. */
    public interface ProgressCallback {
        void onUpdate(int executed, int failed, int total);
    }

    private static final int ACTIVE = 0;
    private static final int TIMED_OUT = 1;
    private static final int INTERRUPTED = 2;
    private static final int COMPLETED = 3;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "offline-import-sql-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private final Connection connection;
    private final ExecutionLogBuffer log;
    private final int timeoutSeconds;

    public SqlScriptImportRunner(Connection connection, ExecutionLogBuffer log) {
        this(connection, log, 300);
    }

    public SqlScriptImportRunner(Connection connection, ExecutionLogBuffer log, int timeoutSeconds) {
        this.connection = connection;
        this.log = log;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    /** Checks JDBC timeout support before the service creates or changes the target schema. */
    public void verifyTimeoutSupport() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
        }
    }

    /** Checks the vendor COPY API before the service creates or changes the target schema. */
    public void verifyCopySupport() throws SQLException {
        try {
            Object manager = copyManager(connection);
            manager.getClass().getMethod("copyIn", String.class, java.io.Reader.class);
        } catch (ReflectiveOperationException e) {
            throw new SQLException("当前 Kingbase JDBC 驱动不支持 COPY FROM STDIN", "0A000");
        }
    }

    /** Runs {@code prelude} outside the statement counters. */
    public void runPrelude(List<String> preludeStatements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            for (String sql : preludeStatements) {
                checkInterrupted();
                log.line("执行会话预置语句: " + statementKind(sql));
                try {
                    statement.execute(sql);
                } catch (SQLException e) {
                    throw safeSqlException("SQL 会话初始化失败", e);
                }
            }
        }
    }

    /** Compatibility entry point for SQL-only callers. */
    public String execute(List<String> statements, boolean stopOnError, ProgressCallback callback)
            throws SQLException {
        return executePlan(statements.stream().map(SqlScriptSplitter.Statement::sql).toList(), stopOnError, callback);
    }

    /** Returns a safe failure summary when the run aborted or statements failed, otherwise null. */
    public String executePlan(
            List<SqlScriptSplitter.Statement> statements, boolean stopOnError, ProgressCallback callback)
            throws SQLException {
        int failed = 0;
        try (Statement jdbc = connection.createStatement()) {
            jdbc.setQueryTimeout(timeoutSeconds);
            for (int index = 0; index < statements.size(); index++) {
                if (Thread.currentThread().isInterrupted()) {
                    callback.onUpdate(index, failed, statements.size());
                    return "SQL 导入任务已中断";
                }
                SqlScriptSplitter.Statement unit = statements.get(index);
                String sql = unit.sql();
                try {
                    if (unit.isCopy()) {
                        executeCopy(sql, unit.copyData());
                    } else {
                        executeStatement(jdbc, sql);
                    }
                    log.line("OK " + (index + 1) + "/" + statements.size() + " " + statementKind(sql));
                } catch (SQLException e) {
                    failed++;
                    String failure = failureCode(e);
                    log.line("失败 " + (index + 1) + "/" + statements.size() + " "
                            + statementKind(sql) + " => " + failure);
                    if (Thread.currentThread().isInterrupted()) {
                        callback.onUpdate(index + 1, failed, statements.size());
                        return "SQL 导入任务已中断";
                    }
                    if (stopOnError || unit.isCopy() || connection.isClosed()) {
                        callback.onUpdate(index + 1, failed, statements.size());
                        return "第 " + (index + 1) + " 条语句执行失败，已停止（" + failure + "）";
                    }
                } catch (ReflectiveOperationException e) {
                    failed++;
                    log.line("失败 " + (index + 1) + "/" + statements.size() + " COPY => JDBC COPY API 不可用");
                    callback.onUpdate(index + 1, failed, statements.size());
                    return "第 " + (index + 1) + " 条 COPY 数据导入失败，已停止";
                }
                if ((index + 1) % 20 == 0 || index + 1 == statements.size()) {
                    callback.onUpdate(index + 1, failed, statements.size());
                }
            }
        }
        return failed > 0 ? "共 " + failed + "/" + statements.size() + " 条语句执行失败，详见执行日志" : null;
    }

    private void executeStatement(Statement statement, String sql) throws SQLException {
        AtomicInteger state = new AtomicInteger(ACTIVE);
        Thread worker = Thread.currentThread();
        ScheduledFuture<?> watcher = WATCHDOG.scheduleAtFixedRate(() -> {
            if (worker.isInterrupted() && state.compareAndSet(ACTIVE, INTERRUPTED)) {
                cancelQuietly(statement);
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        try {
            statement.execute(sql);
            if (state.get() == INTERRUPTED) {
                throw new SQLException("interrupted", "57014");
            }
        } finally {
            state.compareAndSet(ACTIVE, COMPLETED);
            watcher.cancel(false);
        }
    }

    private void executeCopy(String sql, String data) throws SQLException, ReflectiveOperationException {
        Object manager = copyManager(connection);
        Method copyIn = manager.getClass().getMethod("copyIn", String.class, java.io.Reader.class);
        AtomicInteger state = new AtomicInteger(ACTIVE);
        Thread worker = Thread.currentThread();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        ScheduledFuture<?> watcher = WATCHDOG.scheduleAtFixedRate(() -> {
            if (worker.isInterrupted() && state.compareAndSet(ACTIVE, INTERRUPTED)) {
                abortConnection();
            } else if (System.nanoTime() >= deadline && state.compareAndSet(ACTIVE, TIMED_OUT)) {
                abortConnection();
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        try {
            copyIn.invoke(manager, sql, new StringReader(data));
            if (state.get() == TIMED_OUT) {
                throw new SQLException("COPY timed out", "57014");
            }
            if (state.get() == INTERRUPTED) {
                throw new SQLException("COPY interrupted", "57014");
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("COPY execution failed", "XX000");
        } finally {
            state.compareAndSet(ACTIVE, COMPLETED);
            watcher.cancel(false);
        }
    }

    private Object copyManager(Connection jdbcConnection) throws ReflectiveOperationException, SQLException {
        ClassLoader driverLoader = jdbcConnection.getClass().getClassLoader();
        Class<?> baseConnectionClass = Class.forName("com.kingbase8.core.BaseConnection", true, driverLoader);
        Class<?> managerClass = Class.forName("com.kingbase8.copy.CopyManager", true, driverLoader);
        Object baseConnection = jdbcConnection.unwrap(baseConnectionClass);
        Constructor<?> constructor = managerClass.getConstructor(baseConnectionClass);
        return constructor.newInstance(baseConnection);
    }

    private void abortConnection() {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | AbstractMethodError ignored) {
            try {
                connection.close();
            } catch (SQLException ignoredClose) {
                // The timed-out or interrupted connection is no longer reusable.
            }
        }
    }

    private void cancelQuietly(Statement statement) {
        try {
            statement.cancel();
        } catch (SQLException ignored) {
            // Preserve the worker interruption and let its JDBC operation return.
        }
    }

    private void checkInterrupted() throws SQLException {
        if (Thread.currentThread().isInterrupted()) {
            throw new SQLException("SQL import interrupted", "57014");
        }
    }

    private SQLException safeSqlException(String message, SQLException cause) {
        return new SQLException(message + "（" + failureCode(cause) + "）", cause.getSQLState());
    }

    private String statementKind(String sql) {
        List<String> words = SqlSchemaMapper.words(sql);
        return words.isEmpty() ? "SQL" : words.get(0);
    }

    private String failureCode(SQLException e) {
        String state = e.getSQLState();
        return state == null || state.isBlank() ? e.getClass().getSimpleName() : "SQLState " + state;
    }
}
