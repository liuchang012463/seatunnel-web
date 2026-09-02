package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.Date;

@Data
public class LakeJdbcDriverVO {

    private Long id;

    private String adapter;

    private String fileName;

    private String driverLocation;

    private String driverClass;

    private String sha256;

    private String dorisMd5;

    private Boolean enabled;

    private Boolean verified;

    private String status;

    private Long version;

    private String lastError;

    private Date updateTime;
}
