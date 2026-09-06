package org.apache.seatunnel.web.spi.bean.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class LakeWarehouseConfigVO {

    private String name;

    private String jdbcUrl;

    private String username;

    private boolean passwordConfigured;

    private String driverClass;

    private String driverLocation;

    private String driverSha256;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long systemDataSourceId;

    private Long configVersion;

    private String connStatus;

    private String lastError;

    private boolean configured;
}
