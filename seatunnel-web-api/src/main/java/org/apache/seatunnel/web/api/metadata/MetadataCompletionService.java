package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataColumn;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTable;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationMetadataJobVO;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/** Runs table-description completion inside Web and keeps short-lived job state for polling. */
@Slf4j
@Service
public class MetadataCompletionService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern THINK_BLOCK = Pattern.compile(
            "<think\\b[^>]*>.*?</think\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final int SAMPLE_ROWS = 50;
    private static final int MAX_CELL_CHARS = 40;
    private static final int MAX_RETAINED_JOBS = 1000;
    private static final Duration JOB_RETENTION = Duration.ofHours(24);

    private static final String SYSTEM_PROMPT = """
            你是一个数据资产描述专家。你的任务是根据数据表的名称、字段名、数据类型和示例数据，推断出该表和每个字段的业务含义。

            规则：
            1. 表描述控制在 50 字以内，概括这张表的核心业务用途
            2. 字段描述控制在 15 字以内，简洁说明字段含义
            3. 对于状态字段（如 status、state），如果值是数字，说明可能的枚举含义
            4. 对于时间字段（如 create_time、update_time），说明事件含义
            5. 对于关联字段（如 user_id、order_id），说明关联对象
            6. 请结合示例数据中的值分布和业务特征来推断字段含义
            7. 仅输出 JSON，不要包含 ```json 等 markdown 标记或额外解释文字
            """;

    private final OpenMetadataClient openMetadataClient;
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final TaskExecutor taskExecutor;
    // The job registry stays in this process alongside the executor that runs each job.
    private final ConcurrentMap<String, CompletionJob> jobs = new ConcurrentHashMap<>();

    public MetadataCompletionService(
            OpenMetadataClient openMetadataClient,
            ObjectProvider<ChatClient> chatClientProvider,
            @Qualifier("metadataExplorationExecutor") TaskExecutor taskExecutor) {
        this.openMetadataClient = openMetadataClient;
        this.chatClientProvider = chatClientProvider;
        this.taskExecutor = taskExecutor;
    }

    /** Queues one owned table for background description completion. */
    public DataExplorationMetadataJobVO submit(Long dataSourceId, OpenMetadataTable table) {
        if (table == null || table.getId() == null || table.getFullyQualifiedName() == null) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.METADATA_COMPLETION_ERROR,
                    "Metadata completion requires a resolved OpenMetadata table");
        }

        CompletionJob job = new CompletionJob(
                UUID.randomUUID().toString(), dataSourceId, table.getId(), table.getFullyQualifiedName());
        registerJob(job);
        try {
            taskExecutor.execute(() -> process(job));
        } catch (TaskRejectedException error) {
            jobs.remove(job.jobId, job);
            log.warn("Metadata completion queue is full: jobId={}, tableId={}, errorType={}",
                    job.jobId, table.getId(), error.getClass().getSimpleName());
            throw new MetadataIntegrationException(
                    MetadataErrorCode.METADATA_COMPLETION_ERROR,
                    "Metadata completion queue is full; retry later");
        }
        log.info("Metadata completion queued: jobId={}, dataSourceId={}, tableId={}, fqn={}",
                job.jobId, dataSourceId, table.getId(), table.getFullyQualifiedName());
        return job.snapshot();
    }

    /** Returns a job only when it belongs to the requested data source and table. */
    public DataExplorationMetadataJobVO getJob(
            String jobId, Long dataSourceId, String tableId, String tableFqn) {
        if (jobId == null || jobId.isBlank()) {
            return null;
        }
        cleanupJobs();
        CompletionJob job = jobs.get(jobId);
        if (job == null
                || !dataSourceId.equals(job.dataSourceId)
                || !tableId.equals(job.tableId)
                || !tableFqn.equals(job.tableFqn)) {
            return null;
        }
        return job.snapshot();
    }

    private void process(CompletionJob job) {
        job.markRunning();
        log.info("Metadata completion started: jobId={}, tableId={}, fqn={}",
                job.jobId, job.tableId, job.tableFqn);
        try {
            Map<String, Object> result = generate(job);
            job.complete(result);
            log.info("Metadata completion finished: jobId={}, tableId={}, status={}, failed={}, skipped={}",
                    job.jobId,
                    job.tableId,
                    job.status,
                    result.get("failed"),
                    result.get("skipped"));
        } catch (RuntimeException error) {
            String message = "Metadata completion failed";
            job.fail(message);
            log.warn("Metadata completion worker failed: jobId={}, tableId={}, errorType={}",
                    job.jobId, job.tableId, error.getClass().getSimpleName());
        }
    }

    private Map<String, Object> generate(CompletionJob job) {
        OpenMetadataTable table;
        try {
            table = openMetadataClient.getTable(job.tableId);
        } catch (RuntimeException error) {
            log.warn("Metadata completion table lookup failed: jobId={}, tableId={}, errorType={}",
                    job.jobId, job.tableId, error.getClass().getSimpleName());
            return failedResult(job, "读取 OpenMetadata 表信息失败");
        }
        if (table == null || !job.tableFqn.equals(table.getFullyQualifiedName())) {
            return failedResult(job, "OpenMetadata 表不存在或已变更");
        }

        List<OpenMetadataColumn> columns = safeColumns(table.getColumns());
        if (hasDescriptions(table, columns)) {
            return skippedResult(table);
        }

        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return failedResult(job, "LLM 未配置，请检查模型服务配置");
        }

        JsonNode sampleData;
        try {
            sampleData = openMetadataClient.getTableSampleData(table.getId());
        } catch (RuntimeException error) {
            log.warn("Metadata completion sample lookup failed: jobId={}, tableId={}, errorType={}",
                    job.jobId, table.getId(), error.getClass().getSimpleName());
            return failedResult(job, "读取 OpenMetadata 样例数据失败");
        }

        String prompt = buildPrompt(table, columns, formatSampleData(sampleData, columns));
        String response;
        try {
            response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
                    .options(OpenAiChatOptions.builder()
                            .temperature(0.1)
                            .maxTokens(4096)
                            .build())
                    .call()
                    .content();
        } catch (RuntimeException error) {
            log.warn("Metadata completion LLM request failed: jobId={}, tableId={}, errorType={}",
                    job.jobId, table.getId(), error.getClass().getSimpleName());
            return failedResult(job, "LLM 描述生成失败");
        }

        ObjectNode generated;
        try {
            generated = parseResponse(response);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            log.warn("Metadata completion LLM response was not valid JSON: jobId={}, tableId={}",
                    job.jobId, table.getId());
            return failedResult(job, "LLM 返回非 JSON");
        }

        String tableDescription = text(generated.get("tableDesc"));
        JsonNode columnDescriptions = generated.get("columnDescs");
        if (tableDescription.isBlank()
                && (columnDescriptions == null || !columnDescriptions.isObject()
                || columnDescriptions.isEmpty())) {
            return failedResult(job, "LLM 返回空描述");
        }

        ArrayNode patch = buildPatch(tableDescription, columnDescriptions, table, columns);
        if (!patch.isEmpty()) {
            try {
                openMetadataClient.patchTable(table.getId(), patch);
            } catch (RuntimeException error) {
                log.warn("Metadata completion patch failed: jobId={}, tableId={}, errorType={}",
                        job.jobId, table.getId(), error.getClass().getSimpleName());
                return failedResult(job, "OpenMetadata 描述写回失败");
            }
        }
        return successResult(table, patch.size());
    }

    private static boolean hasDescriptions(OpenMetadataTable table, List<OpenMetadataColumn> columns) {
        return nonBlank(table.getDescription())
                && columns.stream().allMatch(column -> nonBlank(column.getDescription()));
    }

    private static ArrayNode buildPatch(
            String tableDescription,
            JsonNode columnDescriptions,
            OpenMetadataTable table,
            List<OpenMetadataColumn> columns) {
        ArrayNode patch = OBJECT_MAPPER.createArrayNode();
        if (!nonBlank(table.getDescription()) && !tableDescription.isBlank()) {
            addPatch(patch, "/description", tableDescription);
        }
        for (int index = 0; index < columns.size(); index++) {
            OpenMetadataColumn column = columns.get(index);
            if (nonBlank(column.getDescription()) || columnDescriptions == null
                    || !columnDescriptions.isObject()) {
                continue;
            }
            String description = text(columnDescriptions.get(column.getName()));
            if (!description.isBlank()) {
                addPatch(patch, "/columns/" + index + "/description", description);
            }
        }
        return patch;
    }

    private static void addPatch(ArrayNode patch, String path, String description) {
        ObjectNode operation = patch.addObject();
        operation.put("op", "add");
        operation.put("path", path);
        operation.put("value", description);
    }

    private static ObjectNode parseResponse(String response) throws JsonProcessingException {
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("empty LLM response");
        }
        String cleaned = THINK_BLOCK.matcher(response.trim()).replaceAll("").trim();
        if (cleaned.startsWith("```")) {
            int firstLine = cleaned.indexOf('\n');
            cleaned = firstLine < 0 ? "" : cleaned.substring(firstLine + 1);
            int lastFence = cleaned.lastIndexOf("```");
            if (lastFence >= 0) {
                cleaned = cleaned.substring(0, lastFence);
            }
        }
        JsonNode parsed = OBJECT_MAPPER.readTree(cleaned.trim());
        if (parsed == null || !parsed.isObject()) {
            throw new IllegalArgumentException("LLM response must be a JSON object");
        }
        return (ObjectNode) parsed;
    }

    private static String buildPrompt(
            OpenMetadataTable table, List<OpenMetadataColumn> columns, String sampleText) {
        StringBuilder prompt = new StringBuilder("表名：").append(defaultString(table.getName()));
        String database = childFqnComponent(
                table.getDatabaseFullyQualifiedName(), table.getServiceFullyQualifiedName());
        String schema = childFqnComponent(
                table.getSchemaFullyQualifiedName(), table.getDatabaseFullyQualifiedName());
        if (nonBlank(schema)) {
            prompt.append("\n所属 Schema：");
            if (nonBlank(database)) {
                prompt.append(database).append('.');
            }
            prompt.append(schema);
        }
        if (table.getTags() != null && !table.getTags().isEmpty()) {
            prompt.append("\n已有标签：").append(String.join(", ", table.getTags()));
        }
        prompt.append("\n\n字段列表：\n");
        prompt.append(buildColumnTable(columns));
        if (nonBlank(sampleText)) {
            prompt.append("\n\n示例数据：\n").append(sampleText);
        }
        prompt.append("\n\n输出 JSON 格式（严格 JSON，无 markdown 标记）：\n")
                .append("{\n  \"tableDesc\": \"表描述\",\n  \"columnDescs\": {\n")
                .append("    \"字段名1\": \"描述1\"\n  }\n}");
        return prompt.toString();
    }

    private static String buildColumnTable(List<OpenMetadataColumn> columns) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("字段名", "类型", "约束", "现有描述"));
        for (OpenMetadataColumn column : columns) {
            String dataType = defaultString(column.getDataType());
            if (column.getDataLength() != null) {
                dataType += "(" + column.getDataLength() + ")";
            }
            String constraint = switch (defaultString(column.getConstraint())) {
                case "PRIMARY_KEY" -> "PK";
                case "NOT_NULL" -> "NN";
                case "UNIQUE" -> "UK";
                default -> defaultString(column.getConstraint());
            };
            String description = defaultString(column.getDescription());
            rows.add(List.of(
                    defaultString(column.getName()), dataType, constraint,
                    description.length() > 30 ? description.substring(0, 30) : description));
        }

        int[] widths = new int[4];
        for (List<String> row : rows) {
            for (int index = 0; index < widths.length; index++) {
                widths[index] = Math.max(widths[index], row.get(index).length());
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add(formatRow(rows.get(0), widths, " | "));
        List<String> separators = new ArrayList<>(widths.length);
        for (int width : widths) {
            separators.add("-".repeat(width));
        }
        lines.add(String.join("-+-", separators));
        for (int index = 1; index < rows.size(); index++) {
            lines.add(formatRow(rows.get(index), widths, " | "));
        }
        return String.join("\n", lines);
    }

    private static String formatSampleData(JsonNode sample, List<OpenMetadataColumn> columns) {
        JsonNode rows = sample == null ? null : sample.path("sampleData").path("rows");
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            return null;
        }

        List<String> names = new ArrayList<>();
        JsonNode sampleColumns = sample.path("columns");
        if (sampleColumns.isArray()) {
            for (JsonNode column : sampleColumns) {
                names.add(text(column == null ? null : column.get("name")));
            }
        }
        if (names.isEmpty()) {
            names.addAll(columns.stream().map(column -> defaultString(column.getName())).toList());
        }
        int rowCount = Math.min(SAMPLE_ROWS, rows.size());
        List<List<String>> values = new ArrayList<>();
        int[] widths = new int[names.size()];
        for (int index = 0; index < names.size(); index++) {
            widths[index] = Math.min(names.get(index).length(), MAX_CELL_CHARS);
        }
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            JsonNode row = rows.get(rowIndex);
            List<String> cells = new ArrayList<>();
            for (int columnIndex = 0; columnIndex < names.size(); columnIndex++) {
                JsonNode value = row != null && row.isArray() && columnIndex < row.size()
                        ? row.get(columnIndex) : null;
                String cell = value == null || value.isNull()
                        ? "NULL" : value.isTextual() ? value.asText() : value.toString();
                cells.add(cell);
                widths[columnIndex] = Math.min(MAX_CELL_CHARS,
                        Math.max(widths[columnIndex], cell.length()));
            }
            values.add(cells);
        }

        List<String> lines = new ArrayList<>();
        lines.add(formatRow(names, widths, " | "));
        List<String> separators = new ArrayList<>();
        for (int width : widths) {
            separators.add("-".repeat(width));
        }
        lines.add(String.join("-+-", separators));
        for (List<String> row : values) {
            List<String> truncated = row.stream().map(value -> value.length() > MAX_CELL_CHARS
                    ? value.substring(0, MAX_CELL_CHARS) : value).toList();
            lines.add(formatRow(truncated, widths, " | "));
        }
        return String.join("\n", lines);
    }

    private static String formatRow(List<String> values, int[] widths, String separator) {
        List<String> cells = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            int width = widths[index];
            cells.add(value + " ".repeat(Math.max(0, width - value.length())));
        }
        return String.join(separator, cells);
    }

    private static Map<String, Object> skippedResult(OpenMetadataTable table) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table_name", defaultString(table.getName()));
        row.put("success", true);
        row.put("skipped", true);
        return aggregateResult(0, 0, 1, row);
    }

    private static Map<String, Object> successResult(OpenMetadataTable table, int patchCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table_name", defaultString(table.getName()));
        row.put("success", true);
        row.put("skipped", false);
        row.put("patches_count", patchCount);
        return aggregateResult(1, 0, 0, row);
    }

    private static Map<String, Object> failedResult(CompletionJob job, String message) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table_name", job.tableFqn);
        row.put("success", false);
        row.put("error", message);
        row.put("skipped", false);
        return aggregateResult(0, 1, 0, row);
    }

    private static Map<String, Object> aggregateResult(
            int completed, int failed, int skipped, Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", 1);
        result.put("completed", completed);
        result.put("failed", failed);
        result.put("skipped", skipped);
        result.put("results", List.of(row));
        return result;
    }

    private synchronized void registerJob(CompletionJob job) {
        cleanupJobs();
        while (jobs.size() >= MAX_RETAINED_JOBS && evictOldestTerminalJob()) {
            // Make room before accepting another job so the registry remains bounded.
        }
        if (jobs.size() >= MAX_RETAINED_JOBS) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.METADATA_COMPLETION_ERROR,
                    "Metadata completion job registry is full; retry later");
        }
        jobs.put(job.jobId, job);
    }

    private synchronized void cleanupJobs() {
        Instant expiredBefore = Instant.now().minus(JOB_RETENTION);
        jobs.entrySet().removeIf(entry -> entry.getValue().isExpired(expiredBefore));
        while (jobs.size() > MAX_RETAINED_JOBS) {
            if (!evictOldestTerminalJob()) {
                return;
            }
        }
    }

    private boolean evictOldestTerminalJob() {
        CompletionJob oldest = jobs.values().stream()
                .filter(CompletionJob::isTerminal)
                .min(Comparator.comparing(CompletionJob::updatedAt))
                .orElse(null);
        return oldest != null && jobs.remove(oldest.jobId, oldest);
    }

    private static List<OpenMetadataColumn> safeColumns(List<OpenMetadataColumn> columns) {
        return columns == null ? List.of() : columns;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    private static String childFqnComponent(String fullyQualifiedName, String parentFqn) {
        if (!nonBlank(fullyQualifiedName)) {
            return "";
        }
        String parentPrefix = nonBlank(parentFqn) ? parentFqn + '.' : "";
        return fullyQualifiedName.startsWith(parentPrefix)
                ? fullyQualifiedName.substring(parentPrefix.length()) : fullyQualifiedName;
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private final class CompletionJob {

        private final String jobId;
        private final Long dataSourceId;
        private final String tableId;
        private final String tableFqn;
        private final Instant createdAt = Instant.now();
        private String status = "pending";
        private Map<String, Object> progress;
        private Object result;
        private String error;
        private Instant updatedAt = createdAt;

        private CompletionJob(String jobId, Long dataSourceId, String tableId, String tableFqn) {
            this.jobId = jobId;
            this.dataSourceId = dataSourceId;
            this.tableId = tableId;
            this.tableFqn = tableFqn;
        }

        private synchronized void markRunning() {
            status = "running";
            progress = Map.of(
                    "total", 1,
                    "completed", 0,
                    "failed", 0,
                    "skipped", 0,
                    "current", tableFqn);
            updatedAt = Instant.now();
        }

        private synchronized void complete(Map<String, Object> result) {
            status = "completed";
            progress = null;
            this.result = result;
            updatedAt = Instant.now();
        }

        private synchronized void fail(String message) {
            status = "failed";
            progress = null;
            error = message;
            updatedAt = Instant.now();
        }

        private synchronized DataExplorationMetadataJobVO snapshot() {
            DataExplorationMetadataJobVO response = new DataExplorationMetadataJobVO();
            response.setJobId(jobId);
            response.setStatus(status);
            response.setType("generate");
            response.setFullyQualifiedName(tableFqn);
            response.setLevel("table");
            response.setTotalTables(1);
            response.setProgress(progress == null ? null : Map.copyOf(progress));
            response.setResult(result);
            response.setError(error);
            response.setCreatedAt(createdAt.toString());
            response.setUpdatedAt(updatedAt.toString());
            return response;
        }

        private synchronized boolean isTerminal() {
            return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
        }

        private synchronized boolean isExpired(Instant cutoff) {
            return isTerminal() && updatedAt.isBefore(cutoff);
        }

        private synchronized Instant updatedAt() {
            return updatedAt;
        }
    }
}
