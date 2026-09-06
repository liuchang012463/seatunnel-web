package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.ConnStatus;

/** Persisted, server-owned Doris ODS connection configuration. */
@Data
@TableName("t_seatunnel_web_lake_warehouse_config")
@EqualsAndHashCode(callSuper = true)
public class LakeWarehouseConfig extends BaseEntity {

    /** Singleton key; currently always ODS_DORIS. */
    private String configKey;

    private String name;

    private String jdbcUrl;

    private String username;

    /** AES-GCM value encrypted with the existing datasource master key. */
    private String password;

    private String driverClass;

    /** Relative path/name under the shared jdbc-drivers directory. */
    private String driverLocation;

    private String driverSha256;

    /** The system-managed DataSource row used by SeaTunnel task definitions. */
    private Long systemDataSourceId;

    private Long configVersion;

    private ConnStatus connStatus;

    private String lastError;

    private Integer createUserId;

    private Integer updateUserId;
}
