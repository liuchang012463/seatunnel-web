package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Compatibility mapping for historical lake_data_source_id values. */
@Data
@TableName("t_seatunnel_web_lake_datasource_alias")
@EqualsAndHashCode(callSuper = true)
public class LakeDataSourceAlias extends BaseEntity {

    private Long legacyDataSourceId;

    private Long canonicalDataSourceId;

    private String reason;
}
