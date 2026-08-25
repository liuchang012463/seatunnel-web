package org.apache.seatunnel.web.spi.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

/** View of a data source owning unit. */
@Data
public class DataSourceUnitVO {

    private Long id;

    private String unitCode;

    private String unitName;

    private Integer status;

    private String remark;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date updateTime;
}
