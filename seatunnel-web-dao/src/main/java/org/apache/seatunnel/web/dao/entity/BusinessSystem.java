package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Business system master data owned by one {@link DataSourceUnit}. */
@Data
@TableName("t_seatunnel_web_business_system")
@EqualsAndHashCode(callSuper = true)
public class BusinessSystem extends BaseEntity {

    private Integer createUserId;

    private Integer updateUserId;

    private Long unitId;

    private String systemCode;

    private String systemName;

    /** 1 means active, 0 means inactive. */
    private Integer status;

    private String remark;
}
