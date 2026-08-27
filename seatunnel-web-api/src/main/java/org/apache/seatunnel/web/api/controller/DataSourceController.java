package org.apache.seatunnel.web.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.exceptions.ApiException;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.api.service.DataSourceService;
import org.apache.seatunnel.web.api.metadata.MetadataPipelineOperationService;
import org.apache.seatunnel.web.spi.bean.dto.DataSourceDTO;
import org.apache.seatunnel.web.spi.bean.dto.DataSourceExploreDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.bean.vo.DBOptionVO;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceVO;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceMetadataStatusVO;
import org.apache.seatunnel.web.spi.bean.vo.MetadataPipelineRunVO;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import static org.apache.seatunnel.web.spi.enums.Status.*;


@Slf4j
@RestController
@Tag(name = "DATA_SOURCE_TAG")
@RequestMapping("/api/v1/data-source")
public class DataSourceController {

    @Resource
    private DataSourceService dataSourceService;

    @Resource
    private MetadataPipelineOperationService metadataPipelineOperationService;

    /**
     * Creates a new data source.
     */
    @PostMapping
    @Operation(summary = "createDataSource", description = "CREATE_DATA_SOURCE_NOTES")
    @ApiException(CREATE_DATASOURCE_ERROR)
    public Result<DataSource> createDataSource(@RequestBody DataSourceDTO dto) {
        return Result.buildSuc(dataSourceService.createDataSource(dto));
    }

    /**
     * Updates an existing data source.
     */
    @PutMapping("/{id}")
    @Operation(summary = "updateDataSource", description = "UPDATE_DATA_SOURCE_NOTES")
    @Parameters({
            @Parameter(name = "id", description = "DATA_SOURCE_ID", required = true)
    })
    @ApiException(UPDATE_DATASOURCE_ERROR)
    public Result<DataSource> updateDataSource(
            @PathVariable("id") Long id,
            @RequestBody DataSourceDTO dto) {
        return Result.buildSuc(dataSourceService.updateDataSource(id, dto));
    }

    /**
     * Changes only the canonical business-system ownership of a data source.
     * Connection parameters remain untouched, including for job-referenced
     * legacy rows being backfilled during migration.
     */
    @PutMapping("/{id}/ownership")
    @Operation(summary = "assignDataSourceBusinessSystem",
            description = "Assign a data source to an active business system without changing connection parameters")
    @ApiException(UPDATE_DATASOURCE_ERROR)
    public Result<DataSource> assignBusinessSystem(
            @PathVariable("id") Long id,
            @RequestBody DataSourceDTO dto) {
        return Result.buildSuc(dataSourceService.assignBusinessSystem(
                id, dto == null ? null : dto.getBusinessSystemId()));
    }

    /**
     * select a data source by ID.
     */
    @GetMapping("/{id}")
    @Operation(summary = "deleteDataSource", description = "DELETE_DATA_SOURCE_NOTES")
    @Parameters({
            @Parameter(name = "id", description = "DATA_SOURCE_ID", required = true)
    })
    @ApiException(QUERY_DATASOURCE_ERROR)
    public Result<DataSource> selectById(@PathVariable("id") Long id) {
        return Result.buildSuc(dataSourceService.selectById(id));
    }

    /**
     * Deletes a data source by ID.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "deleteDataSource", description = "DELETE_DATA_SOURCE_NOTES")
    @Parameters({
            @Parameter(name = "id", description = "DATA_SOURCE_ID", required = true)
    })
    @ApiException(DELETE_DATA_SOURCE_FAILURE)
    public Result<Boolean> delete(@PathVariable("id") Long id) {
        dataSourceService.delete(id);
        return Result.buildSuc(true);
    }

    /**
     * Checks whether a data source is referenced by any job definition before deletion.
     */
    @GetMapping("/{id}/usage")
    @Operation(summary = "checkDataSourceUsage", description = "CHECK_DATA_SOURCE_USAGE_NOTES")
    @Parameters({
            @Parameter(name = "id", description = "DATA_SOURCE_ID", required = true)
    })
    @ApiException(QUERY_DATASOURCE_ERROR)
    public Result<Boolean> usage(@PathVariable("id") Long id) {
        return Result.buildSuc(dataSourceService.isDataSourceUsed(id));
    }

    /**
     * Updates the business lifecycle status of a data source.
     */
    @PutMapping("/{id}/status")
    @Operation(summary = "updateDataSourceStatus", description = "UPDATE_DATA_SOURCE_STATUS_NOTES")
    @ApiException(UPDATE_DATASOURCE_ERROR)
    public Result<Boolean> updateStatus(
            @PathVariable("id") Long id,
            @RequestBody DataSourceDTO dto) {
        return Result.buildSuc(dataSourceService.updateStatus(
                id,
                dto == null ? null : dto.getStatus()));
    }

    /**
     * Performs pagination query for data sources.
     */
    @PostMapping("/page")
    @Operation(summary = "queryDataSourceListPaging", description = "QUERY_DATA_SOURCE_LIST_PAGING_NOTES")
    @ApiException(QUERY_DATASOURCE_ERROR)
    public PaginationResult<DataSourceVO> queryDataSourceListPaging(@RequestBody DataSourceDTO dto) {
        return dataSourceService.queryDataSourceListPaging(dto);
    }

    /**
     * Retrieves data source options filtered by database type.
     */
    @GetMapping("/option")
    @Operation(summary = "queryDataSourceOptions", description = "QUERY_DATA_SOURCE_OPTIONS_NOTES")
    @Parameters({
            @Parameter(name = "dbType", description = "DATABASE_TYPE", required = true)
    })
    @ApiException(QUERY_DATASOURCE_ERROR)
    public Result<List<DBOptionVO>> option(@RequestParam("dbType") String dbType) {
        return Result.buildSuc(dataSourceService.option(dbType));
    }

    /**
     * Tests connection for an existing data source.
     */
    @GetMapping("/{id}/connect-test")
    @Operation(summary = "connectionTest", description = "CONNECT_DATA_SOURCE_TEST_NOTES")
    @Parameters({
            @Parameter(name = "id", description = "DATA_SOURCE_ID", required = true)
    })
    @ApiException(CONNECTION_TEST_FAILURE)
    public Result<Boolean> connectionTest(@PathVariable("id") Long id) {
        return Result.buildSuc(dataSourceService.connectionTest(id));
    }

    @PostMapping("/{id}/scan")
    @Operation(summary = "triggerDataSourceScan", description = "Trigger a manual OpenMetadata metadata scan")
    public Result<Boolean> triggerScan(@PathVariable("id") Long id) {
        return Result.buildSuc(metadataPipelineOperationService.triggerScan(id));
    }

    @PostMapping("/{id}/explore")
    @Operation(summary = "triggerDataSourceExploration", description = "Trigger one database-scoped data-source exploration")
    public Result<Boolean> triggerExploration(
            @PathVariable("id") Long id,
            @RequestBody DataSourceExploreDTO dto) {
        return Result.buildSuc(metadataPipelineOperationService.triggerExploration(
                id, dto == null ? null : dto.getDatabaseFqn()));
    }

    @GetMapping("/{id}/metadata-status")
    @Operation(summary = "getDataSourceMetadataStatus", description = "Read the local latest scan and exploration cache")
    public Result<DataSourceMetadataStatusVO> metadataStatus(@PathVariable("id") Long id) {
        return Result.buildSuc(metadataPipelineOperationService.getCachedStatus(id));
    }

    @GetMapping("/{id}/metadata-databases")
    @Operation(summary = "listDataSourceMetadataDatabases", description = "List discovered OpenMetadata databases for exploration")
    public Result<List<OptionVO>> metadataDatabases(@PathVariable("id") Long id) {
        return Result.buildSuc(metadataPipelineOperationService.listDatabases(id));
    }

    @GetMapping("/{id}/runs")
    @Operation(summary = "listDataSourceMetadataRuns", description = "Read recent OpenMetadata pipeline status records")
    public Result<List<MetadataPipelineRunVO>> runs(
            @PathVariable("id") Long id,
            @RequestParam(value = "type", defaultValue = "SCAN") String type,
            @RequestParam(value = "limit", defaultValue = "5") int limit) {
        return Result.buildSuc(metadataPipelineOperationService.listRuns(id, type, limit));
    }

    @PostMapping("/{id}/metadata-sync/retry")
    @Operation(summary = "retryDataSourceMetadataSync", description = "Retry a failed OpenMetadata control-plane sync")
    public Result<Boolean> retryMetadataSync(@PathVariable("id") Long id) {
        return Result.buildSuc(metadataPipelineOperationService.retryMetadataSync(id));
    }

    /**
     * Tests connection with provided parameters without saving.
     */
    @PostMapping("/connect-test-with-param")
    @Operation(summary = "connectionTestWithParam", description = "CONNECT_DATA_SOURCE_TEST_WITH_PARAM_NOTES")
    @ApiException(CONNECTION_TEST_FAILURE)
    public Result<Boolean> connectionTestWithParam(@RequestBody Map<String, Object> requestBody) {
        Object connJsonObj = requestBody.get("connJson");
        if (connJsonObj == null || StringUtils.isBlank(String.valueOf(connJsonObj))) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "connJson");
        }
        return Result.buildSuc(dataSourceService.connectionTestWithParam(String.valueOf(connJsonObj)));
    }

    /**
     * Batch deletes multiple data sources.
     */
    @DeleteMapping("/batch")
    @Operation(summary = "batchDeleteDataSource", description = "BATCH_DELETE_DATA_SOURCE_NOTES")
    @ApiException(DELETE_DATA_SOURCE_FAILURE)
    public Result<Boolean> batchDelete(@RequestBody List<Long> ids) {
        return Result.buildSuc(dataSourceService.batchDelete(ids));
    }

    /**
     * Batch tests connections for multiple data sources.
     */
    @PostMapping("/batch-connect-test")
    @Operation(summary = "batchConnectionTest", description = "BATCH_CONNECT_DATA_SOURCE_TEST_NOTES")
    @ApiException(CONNECTION_TEST_FAILURE)
    public Result<Boolean> batchConnectionTest(@RequestBody List<Long> ids) {
        return Result.buildSuc(dataSourceService.batchConnectionTest(ids));
    }

    /**
     * Retrieves all data sources.
     */
    @GetMapping("/all")
    @Operation(summary = "queryAllDataSource", description = "QUERY_ALL_DATA_SOURCE_NOTES")
    @ApiException(QUERY_DATASOURCE_ERROR)
    public Result<List<DataSourceVO>> all() {
        return Result.buildSuc(dataSourceService.listAll());
    }

    /**
     * Lists distinct data source units used by existing data sources.
     */
    @GetMapping("/units")
    @Operation(summary = "queryDataSourceUnits", description = "QUERY_DATA_SOURCE_UNITS_NOTES")
    @ApiException(QUERY_DATASOURCE_ERROR)
    public Result<List<String>> units() {
        return Result.buildSuc(dataSourceService.listDataSourceUnits());
    }

    /**
     * Upload JDBC driver jar.
     */
    @PostMapping(value = "/plugin/driver/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "uploadJdbcDriver", description = "UPLOAD_JDBC_DRIVER_NOTES")
    @ApiException(UPDATE_DATASOURCE_ERROR)
    public Result<Map<String, Object>> uploadJdbcDriver(
            @Parameter(description = "Driver jar file (.jar)", required = true)
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "Plugin type")
            @RequestParam(value = "pluginType", required = false) String pluginType,
            @Parameter(description = "Whether to overwrite existing file")
            @RequestParam(value = "overwrite", required = false, defaultValue = "true") boolean overwrite) {
        return Result.buildSuc(dataSourceService.uploadJdbcDriver(file, pluginType, overwrite));
    }
}
