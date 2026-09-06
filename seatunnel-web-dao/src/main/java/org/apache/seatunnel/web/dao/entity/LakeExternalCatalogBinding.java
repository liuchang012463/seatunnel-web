package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;

import java.util.Date;

@Data
@TableName("t_seatunnel_web_lake_external_catalog_binding")
@EqualsAndHashCode(callSuper = true)
public class LakeExternalCatalogBinding extends LakeResourceEntity {

    private Long lakeDataSourceId;

    private Long sourceDataSourceId;

    private String catalogName;

    private String adapter;

    private LakeCatalogScope scope;

    private String desiredSpecJson;

    private String desiredSpecHash;

    private String credentialRevision;

    /** Server-owned driver inventory hash copied from the desired spec. */
    private String driverChecksum;

    private String validationStatus;

    /** Last safe, non-secret actual catalog snapshot returned by Doris. */
    private String actualSnapshotJson;

    /** Time at which the actual snapshot was observed. */
    private Date lastObservedAt;

    /**
     * API terminology calls this value targetCatalogName.  The persisted
     * column remains catalog_name for compatibility with V1_0_21.
     */
    public String getTargetCatalogName() {
        return catalogName;
    }

    public void setTargetCatalogName(String targetCatalogName) {
        this.catalogName = targetCatalogName;
    }
}
