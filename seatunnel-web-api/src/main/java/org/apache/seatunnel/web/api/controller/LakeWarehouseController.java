package org.apache.seatunnel.web.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.seatunnel.web.api.service.LakeWarehouseService;
import org.apache.seatunnel.web.spi.bean.dto.LakeWarehouseConfigDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.LakeJdbcDriverVO;
import org.apache.seatunnel.web.spi.bean.vo.LakeDorisStatusVO;
import org.apache.seatunnel.web.spi.bean.vo.LakeWarehouseConfigVO;
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

@RestController
@Tag(name = "LAKE_WAREHOUSE_TAG")
@RequestMapping("/api/v1/lake/warehouse")
public class LakeWarehouseController {

    @Resource
    private LakeWarehouseService lakeWarehouseService;

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

    @GetMapping("/status")
    @Operation(summary = "getLakeDorisStatus")
    public Result<LakeDorisStatusVO> status() {
        return Result.buildSuc(lakeWarehouseService.getDorisStatus());
    }

    @GetMapping("/drivers")
    @Operation(summary = "listLakeJdbcDrivers")
    public Result<List<LakeJdbcDriverVO>> listDrivers() {
        return Result.buildSuc(lakeWarehouseService.listDrivers());
    }

    @PostMapping("/drivers/register")
    @Operation(summary = "registerLakeJdbcDriver")
    public Result<LakeJdbcDriverVO> registerDriver(
            @RequestParam String adapter,
            @RequestParam(required = false) String fileName,
            @RequestParam String driverLocation,
            @RequestParam(required = false) String driverClass,
            @RequestParam(required = false) String sha256,
            @RequestParam(required = false) String dorisMd5) {
        return Result.buildSuc(lakeWarehouseService.registerDriver(
                adapter, fileName, driverLocation, driverClass, sha256, dorisMd5));
    }

    @PostMapping(value = "/drivers/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "uploadLakeJdbcDriver")
    public Result<LakeJdbcDriverVO> uploadDriver(
            @Parameter(description = "JDBC jar") @RequestPart("file") MultipartFile file,
            @RequestParam String adapter,
            @RequestParam(required = false) String driverClass,
            @RequestParam(required = false, defaultValue = "true") boolean overwrite) {
        return Result.buildSuc(lakeWarehouseService.uploadDriver(file, adapter, driverClass, overwrite));
    }
}
