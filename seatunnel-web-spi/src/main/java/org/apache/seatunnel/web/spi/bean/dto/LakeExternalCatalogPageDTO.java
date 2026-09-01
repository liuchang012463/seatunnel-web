package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.spi.bean.dto.pagination.PaginationBaseDTO;

/** Read-only local page filters for external catalog bindings. */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LakeExternalCatalogPageDTO extends PaginationBaseDTO {

    private Long lakeDataSourceId;

    private Long sourceDataSourceId;

    private String targetCatalogName;

    private String adapter;

    private String resourceStatus;

    private String validationStatus;
}
