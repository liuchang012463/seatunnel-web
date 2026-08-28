package org.apache.seatunnel.web.api.controller;

import jakarta.annotation.Resource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.seatunnel.web.api.metadata.DataExplorationService;
import org.apache.seatunnel.web.common.QueryResult;
import org.apache.seatunnel.web.spi.bean.dto.DataExplorationMetadataUpdateDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationDatabaseVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationErDiagramVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationProfileVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationMetadataJobVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationSchemaVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationTableDetailVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationTablePageVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Product-facing scan-result and data-exploration APIs. */
@RestController
@RequestMapping("/api/v1/data-exploration")
@Tag(name = "DATA_EXPLORATION_TAG")
public class DataExplorationController {

    @Resource
    private DataExplorationService dataExplorationService;

    @GetMapping("/databases")
    @Operation(summary = "listDataExplorationDatabases", description = "List scanned OpenMetadata databases")
    public Result<List<DataExplorationDatabaseVO>> databases(
            @RequestParam("dataSourceId") Long dataSourceId) {
        return Result.buildSuc(dataExplorationService.listDatabases(dataSourceId));
    }

    @GetMapping("/schemas")
    @Operation(summary = "listDataExplorationSchemas", description = "List scanned OpenMetadata schemas")
    public Result<List<DataExplorationSchemaVO>> schemas(
            @RequestParam("dataSourceId") Long dataSourceId,
            @RequestParam("databaseFqn") String databaseFqn) {
        return Result.buildSuc(dataExplorationService.listSchemas(dataSourceId, databaseFqn));
    }

    @GetMapping("/tables")
    @Operation(summary = "listDataExplorationTables", description = "List scanned OpenMetadata tables")
    public Result<DataExplorationTablePageVO> tables(
            @RequestParam("dataSourceId") Long dataSourceId,
            @RequestParam("databaseFqn") String databaseFqn,
            @RequestParam("schemaFqn") String schemaFqn,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return Result.buildSuc(dataExplorationService.listTables(
                dataSourceId, databaseFqn, schemaFqn, pageNo, pageSize));
    }

    @GetMapping("/tables/{tableId}")
    @Operation(summary = "getDataExplorationTable", description = "Get scanned table structure")
    public Result<DataExplorationTableDetailVO> table(
            @PathVariable("tableId") String tableId,
            @RequestParam("dataSourceId") Long dataSourceId) {
        return Result.buildSuc(dataExplorationService.getTable(dataSourceId, tableId));
    }

    @PatchMapping("/tables/{tableId}/metadata")
    @Operation(summary = "updateDataExplorationTableMetadata", description = "Update editable OpenMetadata table governance fields")
    public Result<DataExplorationTableDetailVO> updateMetadata(
            @PathVariable("tableId") String tableId,
            @RequestParam("dataSourceId") Long dataSourceId,
            @RequestBody DataExplorationMetadataUpdateDTO request) {
        return Result.buildSuc(dataExplorationService.updateMetadata(dataSourceId, tableId, request));
    }

    @PostMapping("/tables/{tableId}/metadata-completion")
    @Operation(summary = "startDataExplorationMetadataCompletion", description = "Submit an asynchronous metadata description completion task")
    public Result<DataExplorationMetadataJobVO> startMetadataCompletion(
            @PathVariable("tableId") String tableId,
            @RequestParam("dataSourceId") Long dataSourceId) {
        return Result.buildSuc(dataExplorationService.startMetadataCompletion(dataSourceId, tableId));
    }

    @GetMapping("/tables/{tableId}/metadata-completion/jobs/{jobId}")
    @Operation(summary = "getDataExplorationMetadataCompletion", description = "Read metadata description completion task status")
    public Result<DataExplorationMetadataJobVO> metadataCompletion(
            @PathVariable("tableId") String tableId,
            @PathVariable("jobId") String jobId,
            @RequestParam("dataSourceId") Long dataSourceId) {
        return Result.buildSuc(dataExplorationService.getMetadataCompletion(dataSourceId, tableId, jobId));
    }

    @GetMapping("/er-diagram")
    @Operation(summary = "getDataExplorationErDiagram", description = "Build ER diagram data from OpenMetadata table constraints")
    public Result<DataExplorationErDiagramVO> erDiagram(
            @RequestParam("dataSourceId") Long dataSourceId,
            @RequestParam("databaseFqn") String databaseFqn,
            @RequestParam(value = "schemaFqn", required = false) String schemaFqn) {
        return Result.buildSuc(dataExplorationService.getErDiagram(dataSourceId, databaseFqn, schemaFqn));
    }

    @GetMapping("/tables/{tableId}/profile")
    @Operation(summary = "getDataExplorationProfile", description = "Get latest successful table profile")
    public Result<DataExplorationProfileVO> profile(
            @PathVariable("tableId") String tableId,
            @RequestParam("dataSourceId") Long dataSourceId) {
        return Result.buildSuc(dataExplorationService.getProfile(dataSourceId, tableId));
    }

    @PostMapping("/tables/{tableId}/preview")
    @Operation(summary = "previewDataExplorationTable", description = "Preview top 20 rows through the data-source catalog")
    public Result<QueryResult> preview(
            @PathVariable("tableId") String tableId,
            @RequestParam("dataSourceId") Long dataSourceId,
            @RequestBody(required = false) Map<String, Object> requestBody) {
        return Result.buildSuc(dataExplorationService.preview(dataSourceId, tableId, requestBody));
    }

}
