package org.apache.seatunnel.web.common.modal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class JobDefinitionAnalysisResult {

    /**
     * Resolved source types, e.g. MYSQL
     */
    private String sourceType;

    /**
     * Resolved sink types, e.g. HIVE
     */
    private String sinkType;

    /**
     * Source datasource id
     */
    private Long sourceDatasourceId;

    /** All source IDs for script jobs; the scalar remains for API compatibility. */
    private List<Long> sourceDatasourceIds;

    /**
     * Sink datasource id
     */
    private Long sinkDatasourceId;

    /** All sink IDs for script jobs; the scalar remains for API compatibility. */
    private List<Long> sinkDatasourceIds;

    /**
     * Source table map json
     */
    private String sourceTable;

    /**
     * Sink table map json
     */
    private String sinkTable;
}
