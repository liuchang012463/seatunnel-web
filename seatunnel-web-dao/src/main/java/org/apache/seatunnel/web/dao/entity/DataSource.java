
package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.ConnStatus;
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
     * 环境
     */
    private EnvironmentEnum environment;
}
