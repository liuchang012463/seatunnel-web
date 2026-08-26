package org.apache.seatunnel.web.spi.bean.dto;

import lombok.Data;

/** Optional scope used by data-inventory and normalized XLSX export APIs. */
@Data
public class DataInventoryFilterDTO {

    private Long unitId;

    private Long businessSystemId;

    private Long dataSourceId;

    private String databaseFqn;
}
