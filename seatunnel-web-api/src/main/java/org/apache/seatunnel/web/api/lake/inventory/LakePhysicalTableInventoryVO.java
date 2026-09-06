package org.apache.seatunnel.web.api.lake.inventory;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only snapshot of one ODS database's registered and discovered tables.
 * Discovered rows are derived from the current Doris table list and are never
 * persisted as lake mappings.
 */
@Data
public class LakePhysicalTableInventoryVO {

    private Long odsDatabaseBindingId;

    private String databaseName;

    private List<String> actualTableNames = new ArrayList<>();

    private List<LakePhysicalTableInventoryTableVO> registeredTables = new ArrayList<>();

    private List<LakePhysicalTableInventoryTableVO> discoveredTables = new ArrayList<>();

    private List<LakePhysicalTableInventoryRelationVO> tableRelations = new ArrayList<>();

    private List<LakePhysicalTableInventoryRelationVO> namespaceRelations = new ArrayList<>();
}
