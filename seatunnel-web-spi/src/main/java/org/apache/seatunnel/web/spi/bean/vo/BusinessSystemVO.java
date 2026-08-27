package org.apache.seatunnel.web.spi.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.Date;

/** View of a business system and its owning unit. */
@Data
public class BusinessSystemVO {

    /** Snowflake IDs exceed JavaScript's safe integer range. */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long unitId;

    private String unitCode;

    private String unitName;

    private String systemCode;

    private String systemName;

    private Integer status;

    private String remark;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date updateTime;
}
