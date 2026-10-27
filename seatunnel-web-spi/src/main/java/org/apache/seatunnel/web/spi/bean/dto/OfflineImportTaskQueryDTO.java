package org.apache.seatunnel.web.spi.bean.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.spi.bean.dto.pagination.PaginationBaseDTO;

/** Filters for the offline import task list. */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Offline import task query")
public class OfflineImportTaskQueryDTO extends PaginationBaseDTO {

    private String name;

    private String importType;

    private String status;
}
