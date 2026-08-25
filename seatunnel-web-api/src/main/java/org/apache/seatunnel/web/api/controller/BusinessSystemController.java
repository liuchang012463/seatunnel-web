package org.apache.seatunnel.web.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.seatunnel.web.api.service.BusinessSystemService;
import org.apache.seatunnel.web.spi.bean.dto.BusinessSystemDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.BusinessSystemVO;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** HTTP API for business-system master data and unit-scoped selectors. */
@RestController
@Tag(name = "BUSINESS_SYSTEM_TAG")
@RequestMapping("/api/v1/business-systems")
public class BusinessSystemController {

    @Resource
    private BusinessSystemService businessSystemService;

    @PostMapping("/page")
    @Operation(summary = "pageBusinessSystems")
    public PaginationResult<BusinessSystemVO> page(@RequestBody BusinessSystemDTO dto) {
        return businessSystemService.pageQuery(dto);
    }

    @GetMapping("/{id}")
    @Operation(summary = "getBusinessSystem")
    public Result<BusinessSystemVO> getById(@PathVariable Long id) {
        return Result.buildSuc(businessSystemService.getById(id));
    }

    @PostMapping
    @Operation(summary = "createBusinessSystem")
    public Result<Long> create(@RequestBody BusinessSystemDTO dto) {
        return Result.buildSuc(businessSystemService.create(dto));
    }

    @PutMapping("/{id}")
    @Operation(summary = "updateBusinessSystem")
    public Result<Boolean> update(@PathVariable Long id, @RequestBody BusinessSystemDTO dto) {
        return Result.buildSuc(businessSystemService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "deleteBusinessSystem")
    public Result<Boolean> delete(@PathVariable Long id) {
        businessSystemService.delete(id);
        return Result.buildSuc(true);
    }

    @GetMapping({"/active", "/list"})
    @Operation(summary = "listActiveBusinessSystemsByUnit")
    public Result<List<BusinessSystemVO>> active(@RequestParam Long unitId) {
        return Result.buildSuc(businessSystemService.listByUnitId(unitId));
    }

    @GetMapping("/options")
    @Operation(summary = "businessSystemOptionsByUnit")
    public Result<List<OptionVO>> options(@RequestParam Long unitId) {
        return Result.buildSuc(businessSystemService.options(unitId));
    }
}
