package org.apache.seatunnel.web.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogScope;
import org.apache.seatunnel.web.api.lake.query.LakeQueryColumnOptionVO;
import org.apache.seatunnel.web.api.lake.query.LakeReadOnlyQueryResultVO;
import org.apache.seatunnel.web.api.service.DataSourceCatalogService;
import org.apache.seatunnel.web.api.service.LakeLogicalCatalogQueryService;
import org.apache.seatunnel.web.api.service.LakeOdsDatabaseService;
import org.apache.seatunnel.web.api.service.LakeWarehouseService;
import org.apache.seatunnel.web.common.QueryResult;
import org.apache.seatunnel.web.spi.bean.vo.ColumnOptionVO;
import org.apache.seatunnel.web.spi.bean.dto.LakeQueryTableIdentityDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeWarehouseConfigDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.LakeJdbcDriverVO;
import org.apache.seatunnel.web.spi.bean.vo.LakeDorisHardwareVO;
import org.apache.seatunnel.web.spi.bean.vo.LakeDorisStatusVO;
import org.apache.seatunnel.web.spi.bean.vo.LakeWarehouseConfigVO;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@Tag(name = "LAKE_WAREHOUSE_TAG")
@RequestMapping("/api/v1/lake/warehouse")
public class LakeWarehouseController {

    @Resource
    private LakeWarehouseService lakeWarehouseService;

    @Resource
    private DataSourceCatalogService dataSourceCatalogService;

    @Resource
    private LakeLogicalCatalogQueryService lakeLogicalCatalogQueryService;

    @Resource
    private LakeOdsDatabaseService lakeOdsDatabaseService;

    @GetMapping
    @Operation(summary = "getLakeWarehouseConfig")
    public Result<LakeWarehouseConfigVO> getConfig() {
        return Result.buildSuc(lakeWarehouseService.getConfig());
    }

    @PutMapping
    @Operation(summary = "saveLakeWarehouseConfig")
    public Result<LakeWarehouseConfigVO> saveConfig(@RequestBody LakeWarehouseConfigDTO request) {
        return Result.buildSuc(lakeWarehouseService.saveConfig(request));
    }

    @PostMapping("/connect-test")
    @Operation(summary = "testLakeWarehouseConfig")
    public Result<LakeWarehouseConfigVO> testConfig(@RequestBody LakeWarehouseConfigDTO request) {
        return Result.buildSuc(lakeWarehouseService.testConfig(request));
    }

    @GetMapping("/catalog/databases")
    @Operation(summary = "listLakeCatalogDatabases")
    public Result<List<OptionVO>> listCatalogDatabases() {
        Long lakeDataSourceId = lakeWarehouseService.requireSystemDataSourceId();
        List<OptionVO> databases = dataSourceCatalogService.listDatabase(lakeDataSourceId).stream()
                .filter(database -> database.getValue() instanceof String name
                        && LakeCatalogScope.isVisibleInternalDatabase(name))
                .toList();
        Map<String, String> labels = lakeOdsDatabaseService.catalogDatabaseLabels(lakeDataSourceId,
                databases.stream().map(database -> (String) database.getValue()).collect(Collectors.toSet()));
        return Result.buildSuc(databases.stream().map(database -> {
            String label = labels.get(database.getValue());
            if (label == null) {
                return database;
            }
            // Keep the physical identity for tree selection and query requests.
            OptionVO option = new OptionVO();
            option.setValue(database.getValue());
            option.setLabel(label);
            option.setDescription(database.getDescription());
            return option;
        })
                .toList());
    }

    @GetMapping("/catalog/tables")
    @Operation(summary = "listLakeCatalogTables")
    public Result<List<OptionVO>> listCatalogTables(@RequestParam("database") String databaseName) {
        return Result.buildSuc(dataSourceCatalogService.listTable(
                lakeWarehouseService.requireSystemDataSourceId(), databaseName));
    }

    @PostMapping("/catalog/columns")
    @Operation(summary = "listLakeCatalogColumns")
    public Result<List<ColumnOptionVO>> listCatalogColumns(@RequestBody Map<String, Object> requestBody) {
        return Result.buildSuc(dataSourceCatalogService.listColumn(
                lakeWarehouseService.requireSystemDataSourceId(), requestBody));
    }

    @PostMapping("/catalog/preview")
    @Operation(summary = "previewLakeCatalogData")
    public Result<QueryResult> previewCatalogData(@RequestBody Map<String, Object> requestBody) {
        // Browsing a sample must not scan an entire lake table just to count it.
        Map<String, Object> sampleRequest = new java.util.HashMap<>(requestBody);
        sampleRequest.put("include_total", false);
        return Result.buildSuc(dataSourceCatalogService.getTop20Data(
                lakeWarehouseService.requireSystemDataSourceId(), sampleRequest));
    }

    @PostMapping("/catalog/sample")
    public Result<LakeReadOnlyQueryResultVO> sample(@RequestBody LakeQueryTableIdentityDTO table) {
        return Result.buildSuc(lakeLogicalCatalogQueryService.sample(table));
    }

    @PostMapping("/catalog/query-columns")
    public Result<List<LakeQueryColumnOptionVO>> queryColumns(@RequestBody LakeQueryTableIdentityDTO table) {
        return Result.buildSuc(lakeLogicalCatalogQueryService.queryColumns(table));
    }

    @GetMapping("/status")
    @Operation(summary = "getLakeDorisStatus")
    public Result<LakeDorisStatusVO> status() {
        return Result.buildSuc(lakeWarehouseService.getDorisStatus());
    }

    @GetMapping("/hardware")
    @Operation(summary = "getLakeDorisHardware")
    public Result<LakeDorisHardwareVO> hardware() {
        return Result.buildSuc(lakeWarehouseService.getDorisHardware());
    }

    @GetMapping("/drivers")
    @Operation(summary = "listLakeJdbcDrivers")
    public Result<List<LakeJdbcDriverVO>> listDrivers() {
        return Result.buildSuc(lakeWarehouseService.listDrivers());
    }

    @PostMapping("/drivers/register")
    @Operation(summary = "registerLakeJdbcDriver")
    public Result<LakeJdbcDriverVO> registerDriver(
            @RequestParam("adapter") String adapter,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestParam("driverLocation") String driverLocation,
            @RequestParam(value = "driverClass", required = false) String driverClass,
            @RequestParam(value = "sha256", required = false) String sha256,
            @RequestParam(value = "dorisMd5", required = false) String dorisMd5) {
        return Result.buildSuc(lakeWarehouseService.registerDriver(
                adapter, fileName, driverLocation, driverClass, sha256, dorisMd5));
    }

    @PostMapping(value = "/drivers/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "uploadLakeJdbcDriver")
    public Result<LakeJdbcDriverVO> uploadDriver(
            @Parameter(description = "JDBC jar") @RequestPart("file") MultipartFile file,
            @RequestParam("adapter") String adapter,
            @RequestParam(value = "driverClass", required = false) String driverClass,
            @RequestParam(value = "overwrite", required = false, defaultValue = "true") boolean overwrite) {
        return Result.buildSuc(lakeWarehouseService.uploadDriver(file, adapter, driverClass, overwrite));
    }
}
