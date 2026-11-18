package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** Source data-source row with its optional ODS database binding. */
@Data
public class LakePhysicalDataSourceVO {

    private Long sourceDataSourceId;
    private String sourceDataSourceName;
    /** Registered database type, used to preselect a compatible lake adapter. */
    private String dbType;
    private Boolean sourceEnabled;
    private Long businessSystemId;
    private Long unitId;
    private String unitCode;
    private String systemCode;
    private Long odsDatabaseBindingId;
    private LakeOdsDatabaseVO odsDatabase;
    /** The original create input for restoring a deleted binding from source detail. */
    private String previousOdsName;
}
