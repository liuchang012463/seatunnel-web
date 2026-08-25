package org.apache.seatunnel.web.spi.bean.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.spi.bean.dto.pagination.PaginationBaseDTO;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Data source unit master data request")
public class DataSourceUnitDTO extends PaginationBaseDTO {

    private Long id;

    private String unitCode;

    private String unitName;

    private Integer status;

    private String remark;
}
