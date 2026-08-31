package org.apache.seatunnel.web.spi.bean.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.apache.seatunnel.web.common.enums.LakeTableModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured MANAGED table preview request.  It intentionally has no DDL
 * field; the server owns contract validation and DDL generation.
 */
@Data
public class LakeManagedTablePreviewDTO {

    @NotNull
    private Long sourceDataSourceId;

    @NotBlank
    private String omEntityId;

    @NotNull
    private Long odsDatabaseBindingId;

    @NotBlank
    private String targetTableName;

    private LakeTableModel tableModel = LakeTableModel.DUPLICATE;

    @Valid
    private List<LakeManagedTableColumnDTO> columns = new ArrayList<>();

    private List<String> keyColumns = new ArrayList<>();

    @Valid
    private LakeManagedTablePartitionDTO partition;

    @Valid
    private LakeManagedTableDistributionDTO distribution;
}
