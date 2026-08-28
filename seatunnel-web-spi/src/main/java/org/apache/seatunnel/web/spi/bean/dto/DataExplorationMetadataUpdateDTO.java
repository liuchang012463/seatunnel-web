package org.apache.seatunnel.web.spi.bean.dto;

import lombok.Data;

import java.util.List;

/** Mutable governance fields accepted by the exploration table facade. */
@Data
public class DataExplorationMetadataUpdateDTO {
    private String displayName;
    private String description;
    private List<String> tags;
    private String domainId;
    private String retentionPeriod;
}
