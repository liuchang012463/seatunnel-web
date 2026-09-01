package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.api.lake.inventory.LakePhysicalTableInventoryVO;

/** Read-only inventory of registered and currently discovered Doris tables. */
public interface LakePhysicalTableInventoryService {

    LakePhysicalTableInventoryVO inventory(Long odsDatabaseBindingId);
}
