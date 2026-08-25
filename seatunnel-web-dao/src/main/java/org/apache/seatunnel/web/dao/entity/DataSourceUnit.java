package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Canonical master data for a data source owning unit. */
@Data
@TableName("t_seatunnel_web_data_source_unit")
@EqualsAndHashCode(callSuper = true)
public class DataSourceUnit extends BaseEntity {

    private Integer createUserId;

    private Integer updateUserId;

    private String unitCode;

    private String unitName;

    /** 1 means active, 0 means inactive. */
    private Integer status;

    private String remark;
}
