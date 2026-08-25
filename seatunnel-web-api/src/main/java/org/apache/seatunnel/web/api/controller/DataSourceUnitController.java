package org.apache.seatunnel.web.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.seatunnel.web.api.service.DataSourceUnitService;
import org.apache.seatunnel.web.spi.bean.dto.DataSourceUnitDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceUnitVO;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** HTTP API for unit master data used by the existing data-source page. */
@RestController
@Tag(name = "DATA_SOURCE_UNIT_TAG")
@RequestMapping("/api/v1/data-source-units")
public class DataSourceUnitController {

    @Resource
    private DataSourceUnitService dataSourceUnitService;

    @PostMapping("/page")
    @Operation(summary = "pageDataSourceUnits")
    public PaginationResult<DataSourceUnitVO> page(@RequestBody DataSourceUnitDTO dto) {
        return dataSourceUnitService.pageQuery(dto);
    }

    @GetMapping("/{id}")
    @Operation(summary = "getDataSourceUnit")
    public Result<DataSourceUnitVO> getById(@PathVariable Long id) {
        return Result.buildSuc(dataSourceUnitService.getById(id));
    }

    @PostMapping
    @Operation(summary = "createDataSourceUnit")
    public Result<Long> create(@RequestBody DataSourceUnitDTO dto) {
        return Result.buildSuc(dataSourceUnitService.create(dto));
    }

    @PutMapping("/{id}")
    @Operation(summary = "updateDataSourceUnit")
    public Result<Boolean> update(@PathVariable Long id, @RequestBody DataSourceUnitDTO dto) {
        return Result.buildSuc(dataSourceUnitService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "deleteDataSourceUnit")
    public Result<Boolean> delete(@PathVariable Long id) {
        dataSourceUnitService.delete(id);
        return Result.buildSuc(true);
    }

    @GetMapping({"/active", "/list"})
    @Operation(summary = "listActiveDataSourceUnits")
    public Result<List<DataSourceUnitVO>> active() {
        return Result.buildSuc(dataSourceUnitService.listActive());
    }

    @GetMapping("/options")
    @Operation(summary = "dataSourceUnitOptions")
    public Result<List<OptionVO>> options() {
        return Result.buildSuc(dataSourceUnitService.options());
    }
}
