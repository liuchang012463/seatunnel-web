package org.apache.seatunnel.web.spi.bean.dto.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class GuideMultiJobContent {

    /**
     * 对齐前端 content.source
     */
    private WorkflowSourceConfig source;

    /**
     * 对齐前端 content.target
     */
    private WorkflowTargetConfig target;

    /**
     * 对齐前端 content.tableMatch
     */
    private TableMatchConfig tableMatch;

    @Data
    public static class WorkflowSourceConfig {
        private String dbType;
        private String connectorType;
        private String datasourceId;
        private String pluginName;
        private Integer fetchSize;
        private Integer splitSize;
        private String serverIdMode;
        @JsonProperty("server-id")
        private String serverId;
        @JsonProperty("slot.name")
        private String slotName;
        private String publicationName;
        @JsonProperty("startup.mode")
        private String startupMode;
        private String topic;
        private String pattern;
        private String consumerGroup;
        private String startMode;
        private Object startModeOffsets;
        private Long startModeTimestamp;
        private Long startModeEndTimestamp;
        private Boolean commitOnCheckpoint;
        private Long pollTimeout;
        private String format;
        private Object schema;
        private String fieldDelimiter;
        private Map<String, Object> kafkaConfig;
    }

    @Data
    public static class WorkflowTargetConfig {
        private String dbType;
        private String connectorType;
        private String datasourceId;
        private String pluginName;
        /** Server-selected ODS binding; never a client-supplied database name. */
        private Long odsDatabaseBindingId;
        private String dataSaveMode;
        private Integer batchSize;
        private String schemaSaveMode;
        private Boolean enableUpsert;
        private String fieldIde;
        private String topic;
        private String format;
        private String semantics;
        private String transactionPrefix;
        private Integer partition;
        private List<String> partitionKeyFields;
        private Map<String, Object> kafkaConfig;
    }

    @Data
    public static class TableMatchConfig {
        /**
         * 前端当前是 "1" / "2" / "3" / "4"
         */
        private String mode;

        /**
         * matchMode 为 1/4 时使用
         */
        private List<String> tables;

        /**
         * matchMode 为 2/3 时使用
         */
        private String keyword;
    }
}
