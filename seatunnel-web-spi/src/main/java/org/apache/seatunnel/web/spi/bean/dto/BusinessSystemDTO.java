package org.apache.seatunnel.web.spi.bean.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.spi.bean.dto.pagination.PaginationBaseDTO;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Business system master data request")
public class BusinessSystemDTO extends PaginationBaseDTO {

    private Long id;

    private Long unitId;

    private String systemCode;

    private String systemName;

    private Integer status;

    private String remark;
}
