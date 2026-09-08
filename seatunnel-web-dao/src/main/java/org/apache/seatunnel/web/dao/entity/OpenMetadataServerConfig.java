package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.ConnStatus;

/** Persisted singleton OpenMetadata control-plane connection. */
@Data
@TableName("t_seatunnel_web_openmetadata_config")
@EqualsAndHashCode(callSuper = true)
public class OpenMetadataServerConfig extends BaseEntity {

    /** Singleton key; always OPENMETADATA. */
    private String configKey;

    /**
     * Legacy column; runtime always treats a configured row as enabled.
     * Kept so existing Flyway schema remains readable.
     */
    private Boolean enabled;

    /** Must include the OpenMetadata /api base path. */
    private String baseUrl;

    /** Bot JWT; never return this field on operator APIs. */
    private String token;

    private Integer connectTimeoutMs;

    private Integer readTimeoutMs;

    private String expectedServerVersion;

    private String expectedIngestionPatch;

    private Long configVersion;

    private ConnStatus connStatus;

    private String lastError;

    private Integer createUserId;

    private Integer updateUserId;
}
