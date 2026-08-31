package org.apache.seatunnel.web.api.lake.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.seatunnel.web.common.enums.LakeTableModel;

import java.util.ArrayList;
import java.util.List;

/** Structural target declaration persisted with an ODS table mapping. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"version", "tableModel", "columns", "keyColumns", "partition", "distribution"})
public class TargetContract {

    public static final int CURRENT_VERSION = 2;

    private Integer version = CURRENT_VERSION;
    private LakeTableModel tableModel = LakeTableModel.DUPLICATE;
    private List<TargetColumn> columns = new ArrayList<>();
    private List<String> keyColumns = new ArrayList<>();
    private TargetPartition partition = TargetPartition.disabled();
    private TargetDistribution distribution = TargetDistribution.random();

    public TargetContract(LakeTableModel tableModel, List<TargetColumn> columns,
                          List<String> keyColumns, TargetPartition partition,
                          TargetDistribution distribution) {
        this.version = CURRENT_VERSION;
        this.tableModel = tableModel;
        this.columns = columns == null ? new ArrayList<>() : new ArrayList<>(columns);
        this.keyColumns = keyColumns == null ? new ArrayList<>() : new ArrayList<>(keyColumns);
        this.partition = partition;
        this.distribution = distribution;
    }

    public TargetContract copy() {
        List<TargetColumn> copiedColumns = columns == null ? null : columns.stream()
                .map(column -> new TargetColumn(column.getSourceName(), column.getSourceOrdinal(),
                        column.getTargetName(), column.getTargetType() == null ? null
                                : new TargetType(column.getTargetType().getBase(), column.getTargetType().getLength(),
                                column.getTargetType().getPrecision(), column.getTargetType().getScale()),
                        column.getNullable(), column.getKey(), column.getPhysicalOrdinal()))
                .toList();
        TargetPartition copiedPartition = partition == null ? null
                : new TargetPartition(partition.getEnabled(), partition.getColumn(), partition.getGranularity());
        TargetDistribution copiedDistribution = distribution == null ? null
                : new TargetDistribution(distribution.getType(), distribution.getColumns(), distribution.getBuckets());
        return new TargetContract(version, tableModel, copiedColumns,
                keyColumns == null ? null : new ArrayList<>(keyColumns), copiedPartition, copiedDistribution);
    }
}
