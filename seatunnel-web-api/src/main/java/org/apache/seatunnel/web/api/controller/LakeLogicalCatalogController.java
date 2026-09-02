package org.apache.seatunnel.web.api.controller;

import jakarta.validation.Valid;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.api.lake.catalog.LakeLogicalCapabilityVO;
import org.apache.seatunnel.web.api.service.LakeLogicalCatalogService;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogPageDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogUpdateDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.LakeExternalCatalogVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
            @PathVariable("sourceDataSourceId") Long sourceDataSourceId,
            @RequestParam(value = "adapter", required = false) String adapter,
            @RequestParam(value = "scope", required = false) LakeCatalogScope scope) {
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

    /**
     * Explicitly asks Doris FE/BE to open a temporary JDBC catalog and issue
     * a bounded metadata request against the source.  The temporary catalog
     * is removed before this request returns.
     */
    @PostMapping("/datasources/{sourceDataSourceId}/capability/probe")
    public Result<LakeLogicalCapabilityVO> probe(
            @PathVariable("sourceDataSourceId") Long sourceDataSourceId,
            @RequestParam(value = "adapter", required = false) String adapter,
            @RequestParam(value = "scope", required = false) LakeCatalogScope scope) {
        LakeJdbcAdapterType parsedAdapter = null;
        boolean adapterProvided = adapter != null && !adapter.isBlank();
        if (adapterProvided) {
            try {
                parsedAdapter = LakeJdbcAdapterType.parse(adapter);
            } catch (IllegalArgumentException ignored) {
                // The service publishes a stable disabled capability.
            }
        }
        if (adapterProvided && parsedAdapter == null) {
            // An explicitly invalid adapter must never fall back to the
            // source's inferred type, because that would turn a malformed
            // probe request into a real source-side operation.
            return Result.buildSuc(service.capability(sourceDataSourceId, null, scope));
        }
        return Result.buildSuc(service.probe(sourceDataSourceId, parsedAdapter, scope));
    }

    @PostMapping("/catalogs/page")
    public PaginationResult<LakeExternalCatalogVO> page(
            @RequestBody(required = false) LakeExternalCatalogPageDTO request) {
        return service.page(request);
    }

    @GetMapping("/catalogs/{id}")
    public Result<LakeExternalCatalogVO> detail(@PathVariable("id") Long id) {
        return Result.buildSuc(service.detail(id));
    }

    @PostMapping("/catalogs")
    public Result<LakeExternalCatalogVO> create(
            @Valid @RequestBody LakeExternalCatalogCreateDTO request) {
        return Result.buildSuc(service.create(request));
    }

    @PutMapping("/catalogs/{id}")
    public Result<LakeExternalCatalogVO> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody LakeExternalCatalogUpdateDTO request) {
        return Result.buildSuc(service.update(id, request));
    }

    @PostMapping("/catalogs/{id}/validate")
    public Result<LakeExternalCatalogVO> validate(@PathVariable("id") Long id) {
        return Result.buildSuc(service.validate(id));
    }

    @PostMapping("/catalogs/{id}/refresh")
    public Result<LakeExternalCatalogVO> refresh(@PathVariable("id") Long id) {
        return Result.buildSuc(service.refresh(id));
    }

    @PostMapping("/catalogs/{id}/reconcile")
    public Result<LakeExternalCatalogVO> reconcile(@PathVariable("id") Long id) {
        return Result.buildSuc(service.reconcile(id));
    }

    @DeleteMapping("/catalogs/{id}")
    public Result<LakeExternalCatalogVO> delete(@PathVariable("id") Long id) {
        return Result.buildSuc(service.delete(id));
    }
}
