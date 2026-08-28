package org.apache.seatunnel.web.api.controller;

import jakarta.annotation.Resource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.seatunnel.web.api.metadata.DataInventoryService;
import org.apache.seatunnel.web.spi.bean.dto.DataInventoryFilterDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.DataInventoryDistributionVO;
import org.apache.seatunnel.web.spi.bean.vo.DataInventoryOverviewVO;
import org.apache.seatunnel.web.spi.bean.vo.DataInventoryProfileCoverageVO;
import org.apache.seatunnel.web.spi.bean.vo.DataInventorySummaryVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Data-inventory aggregation endpoints; no local metadata mirror is used. */
@RestController
@RequestMapping("/api/v1/data-inventory")
@Tag(name = "DATA_INVENTORY_TAG")
public class DataInventoryController {

    @Resource
    private DataInventoryService dataInventoryService;

    @GetMapping("/summary")
    @Operation(summary = "getDataInventorySummary", description = "Read aggregated data-inventory counters")
    public Result<DataInventorySummaryVO> summary(
            @RequestParam(value = "unitId", required = false) Long unitId,
            @RequestParam(value = "businessSystemId", required = false) Long businessSystemId,
            @RequestParam(value = "dataSourceId", required = false) Long dataSourceId,
            @RequestParam(value = "databaseFqn", required = false) String databaseFqn) {
        return Result.buildSuc(dataInventoryService.summary(filter(unitId, businessSystemId, dataSourceId, databaseFqn)));
    }

    @GetMapping("/overview")
    @Operation(summary = "getDataInventoryOverview", description = "Read summary and profile coverage from one inventory snapshot")
    public Result<DataInventoryOverviewVO> overview(
            @RequestParam(value = "unitId", required = false) Long unitId,
            @RequestParam(value = "businessSystemId", required = false) Long businessSystemId,
            @RequestParam(value = "dataSourceId", required = false) Long dataSourceId,
            @RequestParam(value = "databaseFqn", required = false) String databaseFqn) {
        return Result.buildSuc(dataInventoryService.overview(
                filter(unitId, businessSystemId, dataSourceId, databaseFqn)));
    }

    @GetMapping("/distribution/source-type")
    @Operation(summary = "getDataInventorySourceTypeDistribution")
    public Result<List<DataInventoryDistributionVO>> sourceType(
            @RequestParam(value = "unitId", required = false) Long unitId,
            @RequestParam(value = "businessSystemId", required = false) Long businessSystemId,
            @RequestParam(value = "dataSourceId", required = false) Long dataSourceId,
            @RequestParam(value = "databaseFqn", required = false) String databaseFqn) {
        return Result.buildSuc(dataInventoryService.sourceTypeDistribution(
                filter(unitId, businessSystemId, dataSourceId, databaseFqn)));
    }

    @GetMapping("/distribution/unit")
    @Operation(summary = "getDataInventoryUnitDistribution")
    public Result<List<DataInventoryDistributionVO>> unit(
            @RequestParam(value = "unitId", required = false) Long unitId,
            @RequestParam(value = "businessSystemId", required = false) Long businessSystemId,
            @RequestParam(value = "dataSourceId", required = false) Long dataSourceId,
            @RequestParam(value = "databaseFqn", required = false) String databaseFqn) {
        return Result.buildSuc(dataInventoryService.unitDistribution(
                filter(unitId, businessSystemId, dataSourceId, databaseFqn)));
    }

    @GetMapping("/distribution/business-system")
    @Operation(summary = "getDataInventoryBusinessSystemDistribution")
    public Result<List<DataInventoryDistributionVO>> businessSystem(
            @RequestParam(value = "unitId", required = false) Long unitId,
            @RequestParam(value = "businessSystemId", required = false) Long businessSystemId,
            @RequestParam(value = "dataSourceId", required = false) Long dataSourceId,
            @RequestParam(value = "databaseFqn", required = false) String databaseFqn) {
        return Result.buildSuc(dataInventoryService.businessSystemDistribution(
                filter(unitId, businessSystemId, dataSourceId, databaseFqn)));
    }

    @GetMapping("/profile-coverage")
    @Operation(summary = "getDataInventoryProfileCoverage")
    public Result<DataInventoryProfileCoverageVO> profileCoverage(
            @RequestParam(value = "unitId", required = false) Long unitId,
            @RequestParam(value = "businessSystemId", required = false) Long businessSystemId,
            @RequestParam(value = "dataSourceId", required = false) Long dataSourceId,
            @RequestParam(value = "databaseFqn", required = false) String databaseFqn) {
        return Result.buildSuc(dataInventoryService.profileCoverage(
                filter(unitId, businessSystemId, dataSourceId, databaseFqn)));
    }

    private static DataInventoryFilterDTO filter(
            Long unitId, Long businessSystemId, Long dataSourceId, String databaseFqn) {
        DataInventoryFilterDTO filter = new DataInventoryFilterDTO();
        filter.setUnitId(unitId);
        filter.setBusinessSystemId(businessSystemId);
        filter.setDataSourceId(dataSourceId);
        filter.setDatabaseFqn(databaseFqn);
        return filter;
    }
}
