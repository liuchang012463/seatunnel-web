package org.apache.seatunnel.web.api.log;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.utils.HoconSensitiveMaskUtil;
import org.apache.seatunnel.web.common.enums.JobMode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

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
    private static final int MAX_PROMPT_CHARS = 24_000;

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
        JobLogContext context = jobLogService.resolve(instanceId, requestedMode);
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
                log.warn("Spring AI task-log diagnosis failed, falling back to rules, instanceId={}", instanceId, e);
            }
        }

        return ruleBased(instanceId, requestedMode, evidence, chatClient == null ? "RULE" : "RULE_FALLBACK");
    }

    private JobLogFaultDiagnosisResult callModel(ChatClient chatClient,
                                                 JobLogContext context,
                                                 JobLogAnalysisResult analysis,
                                                 List<String> evidence,
                                                 String maskedRuntimeConfig) throws JsonProcessingException {
        String response = chatClient.prompt()
                .system("""
                        你是 SeaTunnel 数据引接故障定位助手。只根据提供的日志、结构化记录和脱敏配置判断故障，禁止臆造。
                        faultType 只能是 COLLECTOR、TRANSPORT、DATA_SOURCE、SYSTEM_COMPONENT 四者之一，分别表示采集端、传输链路、数据源、系统组件。
                        必须只输出 JSON，不要 Markdown，字段为：faultType、faultTypeLabel、confidence、rootCause、affectedStage、evidence、recommendedActions、uncertainties。
                        confidence 为 0 到 1 的数字；evidence 和 recommendedActions 为字符串数组；无法确定时降低 confidence 并填写 uncertainties。
                        """)
                .user(prompt(context, analysis, evidence, maskedRuntimeConfig))
                .call()
                .content();

        if (StringUtils.isBlank(response)) {
            return null;
        }

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
                list(node, "evidence", evidence),
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
        List<JobLogEntry> entries = new ArrayList<>();
        entries.addAll(analysis.errors());
        entries.addAll(analysis.executionFlow());
        entries.addAll(analysis.dataSnapshots());
        if (entries.isEmpty()) {
            entries.addAll(analysis.timeline());
        }
        return entries.stream()
                .map(entry -> "L" + entry.lineNumber() + " [" + entry.source() + "] " + entry.raw())
                .filter(StringUtils::isNotBlank)
                .limit(MAX_EVIDENCE_LINES)
                .toList();
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
        return null;
    }

    private String label(String faultType) {
        return switch (faultType) {
            case COLLECTOR -> "采集端";
            case TRANSPORT -> "传输链路";
            case DATA_SOURCE -> "数据源";
            case SYSTEM_COMPONENT -> "系统组件";
            default -> "未明确";
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
        if (result.startsWith("```") && result.endsWith("```")) {
            int firstLineEnd = result.indexOf('\n');
            return firstLineEnd >= 0
                    ? result.substring(firstLineEnd + 1, result.length() - 3).trim()
                    : result.substring(3, result.length() - 3).trim();
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
