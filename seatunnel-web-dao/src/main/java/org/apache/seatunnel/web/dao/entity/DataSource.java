
package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.ConnStatus;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.EnvironmentEnum;
import org.apache.seatunnel.web.spi.enums.DbType;


@Data
@TableName("t_seatunnel_web_datasource")
@EqualsAndHashCode(callSuper = true)
public class DataSource extends BaseEntity {

    /**
     * 创建人用户 ID。
     */
    private Integer createUserId;

    /**
     * 最近一次业务修改人用户 ID。
     */
    private Integer updateUserId;

    /**
     * 数据源名称
     */
    private String name;

    /**
     * @deprecated use {@link #businessSystemId}; retained for compatibility with historical rows.
     */
    @Deprecated
    private String dataSourceUnit;

    /** Canonical business-system ownership; nullable for historical rows awaiting assignment. */
    @TableField("business_system_id")
    private Long businessSystemId;

    /**
     * 数据源类型
     */
    private DbType dbType;

    /**
     * 数据库连接参数
     */
    private String connectionParams;

    /**
     * 原始json
     */
    private String originalJson;

    /**
     * 描述
     */
    private String remark;

    /**
     * 连接状态
     */
    private ConnStatus connStatus;

    /**
     * 数据源业务生命周期状态
     */
    private DataSourceLifecycleStatus status;

    /**
     * 环境
     */
    private EnvironmentEnum environment;

    /** True when the row is maintained by a system capability instead of a user. */
    private Boolean systemManaged;

    /** Stable owner key for system-managed projections, e.g. LAKE_ODS_DORIS. */
    private String systemKey;
}
