package org.apache.seatunnel.web.api.controller;

import jakarta.validation.Valid;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.api.lake.catalog.LakeLogicalCapabilityVO;
import org.apache.seatunnel.web.api.service.LakeLogicalCatalogService;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogPageDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogCreateDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.LakeExternalCatalogVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only capability and local catalog binding endpoints. */
@RestController
@RequestMapping("/api/v1/lake/logical")
public class LakeLogicalCatalogController {

    private final LakeLogicalCatalogService service;

    @Autowired
    public LakeLogicalCatalogController(LakeLogicalCatalogService service) {
        this.service = service;
    }

    @GetMapping("/datasources/{sourceDataSourceId}/capability")
    public Result<LakeLogicalCapabilityVO> capability(
            @PathVariable Long sourceDataSourceId,
            @RequestParam(required = false) String adapter,
            @RequestParam(required = false) LakeCatalogScope scope) {
        LakeJdbcAdapterType parsedAdapter = null;
        if (adapter != null && !adapter.isBlank()) {
            try {
                parsedAdapter = LakeJdbcAdapterType.parse(adapter);
            } catch (IllegalArgumentException ignored) {
                // The service returns a stable disabled capability for an
                // unsupported adapter instead of exposing parser details.
            }
        }
        if (parsedAdapter == null && (adapter == null || adapter.isBlank())) {
            return Result.buildSuc(service.capability(sourceDataSourceId));
        }
        return Result.buildSuc(service.capability(sourceDataSourceId, parsedAdapter, scope));
    }

    @PostMapping("/catalogs/page")
    public PaginationResult<LakeExternalCatalogVO> page(
            @RequestBody(required = false) LakeExternalCatalogPageDTO request) {
        return service.page(request);
    }

    @GetMapping("/catalogs/{id}")
    public Result<LakeExternalCatalogVO> detail(@PathVariable Long id) {
        return Result.buildSuc(service.detail(id));
    }

    @PostMapping("/catalogs")
    public Result<LakeExternalCatalogVO> create(
            @Valid @RequestBody LakeExternalCatalogCreateDTO request) {
        return Result.buildSuc(service.create(request));
    }

    @PostMapping("/catalogs/{id}/validate")
    public Result<LakeExternalCatalogVO> validate(@PathVariable Long id) {
        return Result.buildSuc(service.validate(id));
    }
}
