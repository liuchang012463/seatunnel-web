package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** Source data-source row with its optional ODS database binding. */
@Data
public class LakePhysicalDataSourceVO {

    private Long sourceDataSourceId;
    private String sourceDataSourceName;
    /** Registered database type, used to preselect a compatible lake adapter. */
    private String dbType;
    private Long businessSystemId;
    private Long unitId;
    private String unitCode;
    private String systemCode;
    private Long odsDatabaseBindingId;
    private LakeOdsDatabaseVO odsDatabase;
}
