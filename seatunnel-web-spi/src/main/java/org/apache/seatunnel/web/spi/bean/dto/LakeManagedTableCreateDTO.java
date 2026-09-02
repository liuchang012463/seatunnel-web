package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.apache.seatunnel.web.common.enums.LakeTableModel;

import java.util.ArrayList;
import java.util.List;

/** Submit a previously previewed managed-table plan. */
@Data
public class LakeManagedTableCreateDTO {

    @NotBlank
    private String planFingerprint;

    private Long sourceDataSourceId;
    private String omEntityId;
    private Long odsDatabaseBindingId;
    private Long lifecyclePolicyId;
    private String targetTableName;
    private LakeTableModel tableModel = LakeTableModel.DUPLICATE;
    private List<LakeManagedTableColumnDTO> columns = new ArrayList<>();
    private List<String> keyColumns = new ArrayList<>();
    private LakeManagedTablePartitionDTO partition;
    private LakeManagedTableDistributionDTO distribution;

    /** @deprecated use {@link #planFingerprint}; retained for old clients only. */
    @Deprecated
    @JsonIgnore
    private String previewToken;

    public String effectivePlanFingerprint() {
        return planFingerprint == null || planFingerprint.isBlank()
                ? previewToken : planFingerprint;
    }
}
