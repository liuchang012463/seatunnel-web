package org.apache.seatunnel.web.spi.bean.dto.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.apache.seatunnel.web.common.enums.ScheduleStatusEnum;

import java.util.List;
import java.util.Map;

@Data
public class JobScheduleConfig {

    private List<ScheduleParamItem> paramsList;

    /**
     * 例如：nextDay
     */
    private String instanceGenerateMode;

    /**
     * 前端统一传：normal / pause / empty
     */
    private String scheduleRunType;

    private String timeoutMode;

    private Integer timeoutValue;

    private String timeoutUnit;

    private String rerunPolicy;

    private Boolean autoRetry;

    private Integer retryTimes;

    private Integer retryInterval;

    private String scheduleType;

    private String hourMode;

    private Map<String, Object> minuteValue;

    private Map<String, Object> hourlyRangeValue;

    private Map<String, Object> hourlyAppointValue;

    private Map<String, Object> dailyValue;

    private Map<String, Object> weeklyValue;

    private String effectType;

    private String cronExpression;

    /**
     * Configuration for a bounded incremental micro-batch.  This field is
     * persisted with the schedule, while runtimeParams is populated only for
     * one execution and must never be written back to the database.
     */
    private IncrementalConfig incremental;

    @JsonIgnore
    private Map<String, String> runtimeParams;

    public ScheduleStatusEnum resolveScheduleStatus() {
        return ScheduleStatusEnum.fromCode(this.scheduleRunType);
    }

    @Data
    public static class IncrementalConfig {

        private Boolean enabled;

        private String watermarkColumn;

        private String initialWatermark;

        private Integer safetyDelaySeconds = 0;

        private Integer overlapSeconds = 0;

        private Integer maxWindowSeconds = 1800;
    }

    @Data
    public static class ScheduleParamItem {

        private String key;

        private String paramName;

        private String paramValue;
    }
}
