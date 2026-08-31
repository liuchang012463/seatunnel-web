package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("t_seatunnel_web_lake_ods_database_binding")
@EqualsAndHashCode(callSuper = true)
public class LakeOdsDatabaseBinding extends LakeResourceEntity {

    private Long lakeDataSourceId;

    private Long sourceDataSourceId;

    private String unitCode;

    private String systemCode;

    private String databaseName;
}
