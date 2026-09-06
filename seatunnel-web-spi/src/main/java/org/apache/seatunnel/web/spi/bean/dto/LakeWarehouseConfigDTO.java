package org.apache.seatunnel.web.spi.bean.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Doris ODS 数据湖配置")
public class LakeWarehouseConfigDTO {

    private String name;

    private String jdbcUrl;

    private String username;

    /** Empty on update means keep the existing password. */
    private String password;

    private String driverClass;

    private String driverLocation;

    private String driverSha256;

    /** Optional existing Doris DataSource to adopt on first setup. */
    private Long adoptDataSourceId;
}
