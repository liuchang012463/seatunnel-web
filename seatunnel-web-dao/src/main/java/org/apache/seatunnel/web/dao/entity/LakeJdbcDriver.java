package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** A locally available JDBC driver registered for a logical lake catalog. */
@Data
@TableName("t_seatunnel_web_lake_jdbc_driver")
@EqualsAndHashCode(callSuper = true)
public class LakeJdbcDriver extends BaseEntity {

    /** MYSQL, POSTGRESQL or ORACLE. */
    private String adapter;

    private String fileName;

    /** Relative path under the shared driver directory. */
    private String driverLocation;

    private String driverClass;

    private String sha256;

    /** Optional Doris catalog checksum retained for Doris syntax compatibility. */
    private String dorisMd5;

    private Boolean enabled;

    private Boolean verified;

    private String status;

    /** Monotonic registration version used to invalidate FE/BE driver caches. */
    private Long version;

    private String lastError;

    private Integer createUserId;

    private Integer updateUserId;
}
