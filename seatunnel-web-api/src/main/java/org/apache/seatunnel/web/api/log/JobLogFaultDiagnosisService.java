package org.apache.seatunnel.web.api.log;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.utils.HoconSensitiveMaskUtil;
import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Combines deterministic evidence extraction with an optional Spring AI
 * explanation. A missing or unavailable model never prevents log inspection.
 */
@Service
@Slf4j
public class JobLogFaultDiagnosisService {

    private static final String COLLECTOR = "COLLECTOR";
    private static final String TRANSPORT = "TRANSPORT";
    private static final String DATA_SOURCE = "DATA_SOURCE";
    private static final String SYSTEM_COMPONENT = "SYSTEM_COMPONENT";
    private static final int MAX_EVIDENCE_LINES = 80;
    private static final int MAX_MODEL_LOG_CHARS = 12000;
    private static final int MAX_PROMPT_CHARS = 24_000;
    private static final String MODEL_SYSTEM_PROMPT = """
            你是 SeaTunnel 数据引接故障定位助手。只根据提供的日志、结构化记录和脱敏配置判断故障，禁止臆造。
            faultType 优先使用以下四类之一：COLLECTOR（采集端）、TRANSPORT（传输链路）、DATA_SOURCE（数据源）、SYSTEM_COMPONENT（系统组件）。
            只有当四类都不适用时，才可以定义新的 faultType，并在 faultTypeLabel 中给出清晰中文名称。
            必须只输出 JSON，不要 Markdown，字段为：faultType、faultTypeLabel、confidence、rootCause、affectedStage、evidence、recommendedActions、uncertainties。
            confidence 为 0 到 1 的数字；evidence 和 recommendedActions 为字符串数组；无法确定时降低 confidence 并填写 uncertainties。
            """;

    private final JobLogService jobLogService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ChatClient> chatClientProvider;

    public JobLogFaultDiagnosisService(JobLogService jobLogService,
                                       ObjectMapper objectMapper,
                                       ObjectProvider<ChatClient> chatClientProvider) {
        this.jobLogService = jobLogService;
        this.objectMapper = objectMapper;
        this.chatClientProvider = chatClientProvider;
    }

    public JobLogFaultDiagnosisResult diagnose(Long instanceId, JobMode requestedMode) {
        JobLogContext context = resolveFailedContext(instanceId, requestedMode);
        JobLogAnalysisResult analysis = jobLogService.analyze(instanceId, requestedMode);
        List<String> evidence = evidence(analysis);
        String maskedRuntimeConfig = truncate(
                HoconSensitiveMaskUtil.maskSensitiveInfo(context.runtimeConfig()),
                MAX_PROMPT_CHARS
        );

        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient != null) {
            try {
                JobLogFaultDiagnosisResult aiResult = callModel(
                        chatClient,
                        context,
                        analysis,
                        evidence,
                        maskedRuntimeConfig
                );
                if (aiResult != null) {
                    return aiResult;
                }
            } catch (Exception e) {
                log.warn("AI task-log diagnosis failed, falling back to rules, instanceId={}", instanceId, e);
            }
        }

        return ruleBased(instanceId, requestedMode, evidence, chatClient == null ? "RULE" : "RULE_FALLBACK");
    }

    /**
     * Streams status, model text deltas and a final structured diagnosis for a
     * failed task. A missing/unavailable model uses the same deterministic
     * fallback as the non-streaming endpoint.
     */
    public Flux<JobLogDiagnosisStreamEvent> streamDiagnose(Long instanceId, JobMode requestedMode) {
        return Flux.defer(() -> {
            JobLogContext context = resolveFailedContext(instanceId, requestedMode);
            JobLogAnalysisResult analysis = jobLogService.analyze(instanceId, requestedMode);
            List<String> evidence = evidence(analysis);
            String maskedRuntimeConfig = truncate(
                    HoconSensitiveMaskUtil.maskSensitiveInfo(context.runtimeConfig()),
                    MAX_PROMPT_CHARS
            );
            ChatClient chatClient = chatClientProvider.getIfAvailable();

            if (chatClient == null) {
                return fallbackStream(instanceId, requestedMode, evidence, "RULE");
            }

            String modelPrompt;
            try {
                modelPrompt = prompt(context, analysis, evidence, maskedRuntimeConfig);
                log.info(
                        "Task-log diagnosis model request, instanceId={}, jobMode={}, systemPrompt={}, userPrompt={}",
                        instanceId,
                        requestedMode,
                        safeModelLog(MODEL_SYSTEM_PROMPT),
                        safeModelLog(modelPrompt)
                );
            } catch (JsonProcessingException e) {
                log.warn("Build task-log diagnosis model prompt failed, instanceId={}", instanceId, e);
                return fallbackStream(instanceId, requestedMode, evidence, "RULE_FALLBACK");
            }

            StringBuilder modelOutput = new StringBuilder();
            Flux<String> chunks;
            try {
                chunks = chatClient.prompt()
                        .system(MODEL_SYSTEM_PROMPT)
                        .user(modelPrompt)
                        .stream()
                        .content();
            } catch (Exception e) {
                log.warn("AI task-log diagnosis stream could not start, instanceId={}", instanceId, e);
                return fallbackStream(instanceId, requestedMode, evidence, "RULE_FALLBACK");
            }

            Flux<JobLogDiagnosisStreamEvent> deltas = chunks
                    .filter(StringUtils::isNotBlank)
                    .map(chunk -> {
                        modelOutput.append(chunk);
                        return JobLogDiagnosisStreamEvent.delta(chunk);
                    });
            Mono<JobLogDiagnosisStreamEvent> finalResult = Mono.fromSupplier(() -> {
                String response = modelOutput.toString();
                log.info(
                        "Task-log diagnosis model response, instanceId={}, jobMode={}, response={}",
                        instanceId,
                        requestedMode,
                        safeModelLog(response)
                );
                try {
                    JobLogFaultDiagnosisResult result = parseModelResult(
                            response, context, evidence);
                    if (result != null) {
                        return JobLogDiagnosisStreamEvent.result(result);
                    }
                } catch (Exception e) {
                    log.warn("AI task-log diagnosis stream response was invalid, instanceId={}", instanceId, e);
                }
                return JobLogDiagnosisStreamEvent.result(
                        ruleBased(instanceId, requestedMode, evidence, "RULE_FALLBACK")
                );
            });

            return Flux.concat(
                    Flux.just(JobLogDiagnosisStreamEvent.status("正在读取失败任务的日志、数据快照和执行流程...")),
                    deltas,
                    finalResult,
                    Mono.just(JobLogDiagnosisStreamEvent.done())
            ).onErrorResume(error -> {
                log.warn("AI task-log diagnosis stream failed, instanceId={}", instanceId, error);
                return fallbackStream(instanceId, requestedMode, evidence, "RULE_FALLBACK");
            });
        });
    }

    private Flux<JobLogDiagnosisStreamEvent> fallbackStream(Long instanceId,
                                                              JobMode requestedMode,
                                                              List<String> evidence,
                                                              String provider) {
        JobLogFaultDiagnosisResult result = ruleBased(instanceId, requestedMode, evidence, provider);
        String summary = "已完成规则分析，故障归因：" + result.faultTypeLabel()
                + "。" + result.rootCause();
        return Flux.just(
                JobLogDiagnosisStreamEvent.status("模型服务当前不可用，正在使用规则证据完成定位..."),
                JobLogDiagnosisStreamEvent.delta(summary),
                JobLogDiagnosisStreamEvent.result(result),
                JobLogDiagnosisStreamEvent.done()
        );
    }

    private JobLogContext resolveFailedContext(Long instanceId, JobMode requestedMode) {
        JobLogContext context = jobLogService.resolve(instanceId, requestedMode);
        if (!"FAILED".equalsIgnoreCase(context.jobStatus())) {
            throw new ServiceException("仅状态为 FAILED 的任务实例可以进行故障定位");
        }
        return context;
    }

    private JobLogFaultDiagnosisResult callModel(ChatClient chatClient,
                                                 JobLogContext context,
                                                 JobLogAnalysisResult analysis,
                                                 List<String> evidence,
                                                 String maskedRuntimeConfig) throws JsonProcessingException {
        String response = chatClient.prompt()
                .system(MODEL_SYSTEM_PROMPT)
                .user(logModelRequest(context, analysis, evidence, maskedRuntimeConfig))
                .call()
                .content();

        log.info(
                "Task-log diagnosis model response, instanceId={}, jobMode={}, response={}",
                context.instanceId(),
                context.jobMode(),
                safeModelLog(response)
        );

        if (StringUtils.isBlank(response)) {
            return null;
        }

        return parseModelResult(response, context, evidence);
    }

    private String logModelRequest(JobLogContext context,
                                   JobLogAnalysisResult analysis,
                                   List<String> evidence,
                                   String maskedRuntimeConfig) throws JsonProcessingException {
        String modelPrompt = prompt(context, analysis, evidence, maskedRuntimeConfig);
        log.info(
                "Task-log diagnosis model request, instanceId={}, jobMode={}, systemPrompt={}, userPrompt={}",
                context.instanceId(),
                context.jobMode(),
                safeModelLog(MODEL_SYSTEM_PROMPT),
                safeModelLog(modelPrompt)
        );
        return modelPrompt;
    }

    private String safeModelLog(String value) {
        return truncate(HoconSensitiveMaskUtil.maskSensitiveInfo(StringUtils.defaultString(value)), MAX_MODEL_LOG_CHARS);
    }

    private JobLogFaultDiagnosisResult parseModelResult(String response,
                                                         JobLogContext context,
                                                         List<String> fallbackEvidence) throws JsonProcessingException {

        JsonNode node = objectMapper.readTree(stripCodeFence(response));
        String faultType = normalizeFaultType(text(node, "faultType"));
        if (faultType == null) {
            return null;
        }

        return new JobLogFaultDiagnosisResult(
                context.instanceId(),
                context.jobMode().name(),
                true,
                "SPRING_AI",
                faultType,
                StringUtils.defaultIfBlank(text(node, "faultTypeLabel"), label(faultType)),
                clampConfidence(node.path("confidence").asDouble(0.5)),
                StringUtils.defaultIfBlank(text(node, "rootCause"), "模型未返回明确原因"),
                StringUtils.defaultIfBlank(text(node, "affectedStage"), "未明确"),
                list(node, "evidence", fallbackEvidence),
                list(node, "recommendedActions", List.of("结合证据继续检查对应阶段")),
                list(node, "uncertainties", List.of()),
                Instant.now()
        );
    }

    private String prompt(JobLogContext context,
                          JobLogAnalysisResult analysis,
                          List<String> evidence,
                          String maskedRuntimeConfig) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", context.instanceId());
        payload.put("jobMode", context.jobMode().name());
        payload.put("status", StringUtils.defaultString(context.jobStatus()));
        payload.put("analysis", Map.of(
                "totalLines", analysis.totalLines(),
                "errorCount", analysis.errorCount(),
                "warningCount", analysis.warningCount(),
                "operationCount", analysis.operationRecords().size(),
                "dataSnapshotCount", analysis.dataSnapshots().size(),
                "executionFlowCount", analysis.executionFlow().size()
        ));
        payload.put("evidence", evidence);
        payload.put("runtimeConfig", maskedRuntimeConfig);
        return truncate(objectMapper.writeValueAsString(payload), MAX_PROMPT_CHARS);
    }

    private JobLogFaultDiagnosisResult ruleBased(Long instanceId,
                                                  JobMode requestedMode,
                                                  List<String> evidence,
                                                  String provider) {
        String text = String.join(" ", evidence).toLowerCase(Locale.ROOT);
        String faultType;
        String rootCause;
        String stage;
        List<String> actions;

        if (containsAny(text, "jdbc", "database", "table", "datasource", "data source", "数据源", "connection refused", "authentication")) {
            faultType = DATA_SOURCE;
            rootCause = "数据源连接、认证或读写环节出现异常";
            stage = "数据源";
            actions = List.of("核对数据源地址、端口和凭据", "检查目标表或源表权限与可用性");
        } else if (containsAny(text, "timeout", "socket", "dns", "network", "connection reset", "broken pipe", "传输", "网络", "连接超时")) {
            faultType = TRANSPORT;
            rootCause = "采集端与引接目标之间的传输链路异常或超时";
            stage = "传输链路";
            actions = List.of("检查网络连通性、DNS、端口和防火墙", "确认 Engine 与数据源之间的路由稳定性");
        } else if (containsAny(text, "engine", "rest", "client", "nullpointer", "outofmemory", "classnotfound", "serialization", "系统组件")) {
            faultType = SYSTEM_COMPONENT;
            rootCause = "SeaTunnel Engine、Web 客户端或系统组件运行异常";
            stage = "系统组件";
            actions = List.of("查看 Engine/Web 节点组件堆栈", "核对客户端与 SeaTunnel Engine 版本兼容性");
        } else {
            faultType = COLLECTOR;
            rootCause = "采集任务提交、配置解析或采集执行环节出现异常";
            stage = "采集端";
            actions = List.of("核对任务配置和采集端连接器", "检查任务提交参数与采集端权限");
        }

        return new JobLogFaultDiagnosisResult(
                instanceId,
                requestedMode.name(),
                false,
                provider,
                faultType,
                label(faultType),
                evidence.isEmpty() ? 0.1 : 0.45,
                rootCause,
                stage,
                evidence,
                actions,
                evidence.isEmpty() ? List.of("当前日志没有可识别的错误证据") : List.of("规则分类未替代人工复核"),
                Instant.now()
        );
    }

    private List<String> evidence(JobLogAnalysisResult analysis) {
        List<String> evidence = new ArrayList<>();
        evidence.addAll(analysis.errors().stream()
                .map(entry -> "L" + entry.lineNumber() + " [" + entry.source() + "] " + entry.raw())
                .toList());
        evidence.addAll(analysis.executionFlow().stream().map(this::structuredEvidence).toList());
        evidence.addAll(analysis.dataSnapshots().stream().map(this::structuredEvidence).toList());
        if (evidence.isEmpty()) {
            evidence.addAll(analysis.timeline().stream().map(this::structuredEvidence).toList());
        }
        return evidence.stream()
                .filter(StringUtils::isNotBlank)
                .limit(MAX_EVIDENCE_LINES)
                .toList();
    }

    private String structuredEvidence(JobLogStructuredRecord record) {
        return "L" + record.lineNumber()
                + " [" + record.source() + "] "
                + record.operation()
                + " target=" + record.target()
                + " status=" + record.status()
                + " detail=" + record.detail();
    }

    private List<String> list(JsonNode node, String field, List<String> fallback) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return fallback;
        }
        return objectMapper.convertValue(value, new TypeReference<>() {
        });
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String normalizeFaultType(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("采集") || normalized.equals("COLLECTOR")) {
            return COLLECTOR;
        }
        if (normalized.contains("传输") || normalized.equals("TRANSPORT")) {
            return TRANSPORT;
        }
        if (normalized.contains("数据源") || normalized.equals("DATA_SOURCE")) {
            return DATA_SOURCE;
        }
        if (normalized.contains("系统") || normalized.equals("SYSTEM_COMPONENT")) {
            return SYSTEM_COMPONENT;
        }
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private String label(String faultType) {
        return switch (faultType) {
            case COLLECTOR -> "采集端";
            case TRANSPORT -> "传输链路";
            case DATA_SOURCE -> "数据源";
            case SYSTEM_COMPONENT -> "系统组件";
            default -> faultType;
        };
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private double clampConfidence(double confidence) {
        return Math.max(0, Math.min(1, confidence));
    }

    private String stripCodeFence(String value) {
        String result = value.trim();
        int thinkEnd = result.indexOf("</think>");
        if (thinkEnd >= 0) {
            result = result.substring(thinkEnd + "</think>".length()).trim();
        }

        if (result.startsWith("```") && result.endsWith("```")) {
            int firstLineEnd = result.indexOf('\n');
            result = firstLineEnd >= 0
                    ? result.substring(firstLineEnd + 1, result.length() - 3).trim()
                    : result.substring(3, result.length() - 3).trim();
        }

        int objectStart = result.indexOf('{');
        int objectEnd = result.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return result.substring(objectStart, objectEnd + 1);
        }

        return result;
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n...[truncated]";
    }
}
