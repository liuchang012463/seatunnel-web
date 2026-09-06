package org.apache.seatunnel.web.api.lake.table;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Impact and confirmation fingerprint for MANAGED table deletion. */
@Data
public class LakeManagedTableDeleteImpactVO {

    private Long mappingId;

    private String targetTableName;

    private boolean actualTableExists;

    private boolean lifecycleBound;

    private boolean allowed;

    private String impactHash;

    private List<LakeManagedTableRelationImpactVO> relations = new ArrayList<>();

    private List<String> blockers = new ArrayList<>();
}
