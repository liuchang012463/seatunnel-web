package org.apache.seatunnel.web.spi.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.Date;

/** View of a data source owning unit. */
@Data
public class DataSourceUnitVO {

    /** Snowflake IDs exceed JavaScript's safe integer range. */
    @JsonSerialize(using = ToStringSerializer.class)
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
