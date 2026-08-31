package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;

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

    private String validationStatus;
}
