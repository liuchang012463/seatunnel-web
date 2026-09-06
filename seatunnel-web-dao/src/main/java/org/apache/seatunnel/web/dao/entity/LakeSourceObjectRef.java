package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.LakeSourceObjectType;

@Data
@TableName("t_seatunnel_web_lake_source_object_ref")
@EqualsAndHashCode(callSuper = true)
public class LakeSourceObjectRef extends LakeResourceEntity {

    private Long sourceDataSourceId;

    private String omEntityId;

    private String omFqn;

    private LakeSourceObjectType objectType;

    private String sourceSchemaHash;

    private String sourceSnapshotJson;
}
