package org.apache.seatunnel.web.spi.bean.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Secret-safe local read model for a logical external catalog binding.
 * Desired JSON, operation tokens and raw JDBC properties are intentionally
 * not part of this projection.
 */
@Data
public class LakeExternalCatalogVO {

    private Long id;
    private Long lakeDataSourceId;
    private Long sourceDataSourceId;
    private String targetCatalogName;
    private String adapter;
    private LakeCatalogScope scope;
    /** Non-secret scope selections needed to render a faithful update form. */
    private List<String> databaseInclude = List.of();
    private List<String> tableInclude = List.of();
    private String desiredSpecHash;
    /** Retained only for binary compatibility with the historical schema. */
    @JsonIgnore
    @Deprecated
    private String credentialRevision;
    private String driverChecksum;
    private String validationStatus;
    private LakeResourceStatus resourceStatus;
    private Integer generation;
    private Integer lockVersion;
    private String errorCode;
    private String errorMessage;
    private Map<String, Object> actualSnapshot = new LinkedHashMap<>();
    private Date lastObservedAt;
    private Date lastReconcileAt;
    private Integer createUserId;
    private Integer updateUserId;
    private Boolean deleted;
    private Date createTime;
    private Date updateTime;
}
