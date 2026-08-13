package org.apache.seatunnel.web.core.time;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the canonical source-node incremental configuration and keeps the
 * old schedule-level representation available to the existing HOCON/runtime
 * code paths.
 */
public final class IncrementalConfigResolver {

    public static final int DEFAULT_SAFETY_DELAY_SECONDS = 0;
    public static final int DEFAULT_OVERLAP_SECONDS = 0;
    public static final String DEFAULT_START_VALUE = "1970-01-01 00:00:00";
    public static final String DEFAULT_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    private IncrementalConfigResolver() {
    }

    /**
     * Resolve source-node config first, then fall back to the legacy schedule
     * fields. The returned value is also attached to the schedule so existing
     * builders and runtime renderers can keep their internal contract.
     */
    public static JobScheduleConfig.IncrementalConfig resolve(
            Map<String, Object> workflow,
            JobScheduleConfig schedule) {
        if (schedule == null) {
            return null;
        }

        Map<String, Object> sourceConfig = findSourceConfig(workflow);
        Map<String, Object> sourceIncremental = asMap(sourceConfig.get("incrementalConfig"));
        boolean httpSource = "HTTP".equalsIgnoreCase(firstNonBlank(
                sourceConfig.get("dbType"), sourceConfig.get("sourceDbType")));
        JobScheduleConfig.IncrementalConfig legacy = schedule.getIncremental();
        boolean sourceConfigPresent = sourceConfig.containsKey("incrementalConfig");
        Boolean sourceEnabled = booleanValue(sourceIncremental.get("enabled"));
        boolean enabled = sourceEnabled != null
                ? sourceEnabled
                : sourceConfigPresent
                ? !sourceIncremental.isEmpty()
                : legacy != null && Boolean.TRUE.equals(legacy.getEnabled());

        if (!enabled) {
            return null;
        }

        String fieldName = sourceConfigPresent
                ? firstNonBlank(sourceIncremental.get("fieldName"))
                : firstNonBlank(
                sourceIncremental.get("fieldName"),
                legacy == null ? null : legacy.getWatermarkColumn()
        );
        String startValue = sourceConfigPresent
                ? firstNonBlank(
                sourceIncremental.get("startValue"),
                httpSource ? DEFAULT_START_VALUE : null)
                : firstNonBlank(
                sourceIncremental.get("startValue"),
                legacy == null ? null : legacy.getInitialWatermark(),
                httpSource ? DEFAULT_START_VALUE : null
        );
        String timeFormat = sourceConfigPresent
                ? sourceTimeFormat(workflow)
                : firstNonBlank(
                legacy == null ? null : legacy.getTimeFormat(),
                DEFAULT_TIME_FORMAT
        );

        int maxWindow = IncrementalScheduleIntervalResolver.resolveSeconds(schedule);
        // Keep explicitly persisted legacy runtime values when an old task is
        // opened and its source node is normalized to the canonical shape.
        // New tasks do not carry these hidden fields and therefore use 0/0.
        int safetyDelay = valueOrDefault(
                legacy == null ? null : legacy.getSafetyDelaySeconds(),
                DEFAULT_SAFETY_DELAY_SECONDS);
        int overlap = valueOrDefault(
                legacy == null ? null : legacy.getOverlapSeconds(),
                DEFAULT_OVERLAP_SECONDS);
        overlap = clampOverlap(overlap, maxWindow);

        if (legacy == null) {
            legacy = new JobScheduleConfig.IncrementalConfig();
        }
        legacy.setEnabled(true);
        legacy.setWatermarkColumn(fieldName);
        legacy.setInitialWatermark(startValue);
        legacy.setTimeFormat(timeFormat);
        legacy.setSafetyDelaySeconds(safetyDelay);
        legacy.setOverlapSeconds(overlap);
        legacy.setMaxWindowSeconds(maxWindow);
        schedule.setIncremental(legacy);
        return legacy;
    }

    /**
     * Populate the new source-node shape when an old task is opened for edit.
     * This deliberately does not copy hidden runtime tuning values into the
     * source node; those values remain server defaults/compatibility data.
     */
    public static void normalizeLegacySourceConfig(
            Map<String, Object> workflow,
            JobScheduleConfig schedule) {
        if (workflow == null || schedule == null || findSourceConfig(workflow).isEmpty()) {
            return;
        }
        Map<String, Object> sourceConfig = findSourceConfig(workflow);
        if (sourceConfig.containsKey("incrementalConfig")) {
            return;
        }
        JobScheduleConfig.IncrementalConfig legacy = schedule.getIncremental();
        if (legacy == null || !Boolean.TRUE.equals(legacy.getEnabled())) {
            return;
        }

        Map<String, Object> sourceIncremental = new HashMap<>();
        sourceIncremental.put("enabled", true);
        sourceIncremental.put("fieldName", legacy.getWatermarkColumn());
        sourceIncremental.put("startValue", firstNonBlank(
                legacy.getInitialWatermark(), DEFAULT_START_VALUE));
        if (StringUtils.isNotBlank(legacy.getTimeFormat())) {
            sourceIncremental.put("timeFormat", legacy.getTimeFormat());
        }
        sourceConfig.put("incrementalConfig", sourceIncremental);
    }

    public static Map<String, Object> findSourceConfig(Map<String, Object> workflow) {
        if (workflow == null) {
            return Collections.emptyMap();
        }
        Object rawNodes = workflow.get("nodes");
        if (rawNodes instanceof List) {
            for (Object rawNode : (List<?>) rawNodes) {
                if (!(rawNode instanceof Map)) {
                    continue;
                }
                Map<String, Object> node = castMap(rawNode);
                Map<String, Object> data = asMap(node.get("data"));
                if (!"source".equalsIgnoreCase(String.valueOf(data.get("nodeType")))) {
                    continue;
                }
                Map<String, Object> config = asMap(data.get("config"));
                return config.isEmpty() ? data : config;
            }
        }
        return asMap(workflow.get("source"));
    }

    public static Map<String, Object> sourceIncrementalConfig(Map<String, Object> workflow) {
        return asMap(findSourceConfig(workflow).get("incrementalConfig"));
    }

    public static boolean hasCanonicalSourceConfig(Map<String, Object> workflow) {
        return findSourceConfig(workflow).containsKey("incrementalConfig");
    }

    public static boolean isHttpSource(Map<String, Object> workflow) {
        Map<String, Object> sourceConfig = findSourceConfig(workflow);
        return "HTTP".equalsIgnoreCase(firstNonBlank(
                sourceConfig.get("dbType"), sourceConfig.get("sourceDbType")));
    }

    public static String sourceTimeFormat(Map<String, Object> workflow) {
        String timeFormat = firstNonBlank(sourceIncrementalConfig(workflow).get("timeFormat"));
        return StringUtils.defaultIfBlank(timeFormat, DEFAULT_TIME_FORMAT);
    }

    private static int clampOverlap(int overlap, int maxWindow) {
        if (maxWindow <= 1) {
            return 0;
        }
        return Math.max(0, Math.min(overlap, maxWindow - 1));
    }

    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? castMap(value) : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        return null;
    }

    private static int valueOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static String firstNonBlank(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }
}
