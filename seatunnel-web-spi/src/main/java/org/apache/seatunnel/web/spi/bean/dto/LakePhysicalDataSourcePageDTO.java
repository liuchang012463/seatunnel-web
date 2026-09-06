package org.apache.seatunnel.web.spi.bean.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.spi.bean.dto.pagination.PaginationBaseDTO;

/** Query-only pagination for physical lake source resources. */
@Data
@EqualsAndHashCode(callSuper = true)
public class LakePhysicalDataSourcePageDTO extends PaginationBaseDTO {

    private String keyword;

    private String resourceStatus;
}
