package org.apache.seatunnel.web.spi.bean.dto;

import lombok.Data;

/** User confirmation data for a destructive MANAGED table delete. */
@Data
public class LakeManagedTableDeleteDTO {

    /** The UI must echo the displayed target name before deletion. */
    private String targetTableName;

    /** Hash returned by the latest impact read; stale impact is rejected. */
    private String impactHash;
}
