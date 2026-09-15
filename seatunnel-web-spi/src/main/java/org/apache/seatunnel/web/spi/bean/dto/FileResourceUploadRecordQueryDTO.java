package org.apache.seatunnel.web.spi.bean.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.spi.bean.dto.pagination.PaginationBaseDTO;

/** Filters for the file resource upload audit list. */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "File resource upload record query")
public class FileResourceUploadRecordQueryDTO extends PaginationBaseDTO {

    private String status;

    private String targetPath;
}
