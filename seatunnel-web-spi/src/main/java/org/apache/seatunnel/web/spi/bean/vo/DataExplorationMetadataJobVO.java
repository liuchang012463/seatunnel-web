package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.Map;

/** Product-facing projection of an asynchronous metadata-completion job. */
@Data
public class DataExplorationMetadataJobVO {

    private String jobId;
    private String status;
    private String type;
    private String fullyQualifiedName;
    private String level;
    private Integer totalTables;
    private Map<String, Object> progress;
    private Object result;
    private String error;
    private String createdAt;
    private String updatedAt;
}
