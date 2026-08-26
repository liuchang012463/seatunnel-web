package org.apache.seatunnel.web.spi.bean.vo;


import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.apache.seatunnel.web.common.enums.ConnStatus;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.EnvironmentEnum;
import org.apache.seatunnel.web.common.enums.MetadataRunStatus;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.Date;

@Data
public class DataSourceVO {

    private Long id;

    private Integer createUserId;

    private Integer updateUserId;

    private String name;

    private String dataSourceUnit;

    private Long businessSystemId;

    private Long unitId;

    private String unitCode;

    private String unitName;

    private String systemCode;

    private String businessSystemName;

    private DbType dbType;

    private String jdbcUrl;

    private String remark;

    private String connectionParams;

    private String originalJson;

    private ConnStatus connStatus;

    private DataSourceLifecycleStatus status;

    private EnvironmentEnum environment;

    private String environmentName;

    /** READY/PENDING/...; historical rows without a Binding return NOT_INITIALIZED. */
    private String metadataSyncStatus;

    private MetadataRunStatus scanStatus;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date scanLastRunTime;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date scanLastSuccessTime;

    private MetadataRunStatus profileStatus;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date profileLastRunTime;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date profileLastSuccessTime;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date updateTime;

}
