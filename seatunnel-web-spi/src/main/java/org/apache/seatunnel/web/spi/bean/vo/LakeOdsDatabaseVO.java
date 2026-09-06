package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;

import java.util.Date;

/** ODS database binding projection; credentials and connection JSON are absent. */
@Data
public class LakeOdsDatabaseVO {

    private Long id;
    private Long lakeDataSourceId;
    private Long sourceDataSourceId;
    private String unitCode;
    private String systemCode;
    private String databaseName;
    private LakeResourceStatus resourceStatus;
    private Integer generation;
    private Integer lockVersion;
    private String errorCode;
    private String errorMessage;
    private Date lastReconcileAt;
    private Integer createUserId;
    private Integer updateUserId;
    private Boolean deleted;
    private Date createTime;
    private Date updateTime;
}
