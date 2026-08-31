package org.apache.seatunnel.web.api.controller;

import jakarta.validation.Valid;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTableDeleteImpactVO;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTablePreviewVO;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTableVO;
import org.apache.seatunnel.web.api.service.LakeManagedTableService;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTableCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTableDeleteDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTablePreviewDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** v1.4 MANAGED ODS table endpoints. */
@RestController
@RequestMapping("/api/v1/lake/physical/tables")
public class LakeManagedTableController {

    private final LakeManagedTableService service;

    @Autowired
    public LakeManagedTableController(LakeManagedTableService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    public Result<LakeManagedTablePreviewVO> preview(
            @Valid @RequestBody LakeManagedTablePreviewDTO request) {
        return Result.buildSuc(service.preview(request));
    }

    @PostMapping
    public Result<LakeManagedTableVO> create(
            @Valid @RequestBody LakeManagedTableCreateDTO request) {
        return Result.buildSuc(service.create(request));
    }

    @GetMapping("/{id}")
    public Result<LakeManagedTableVO> detail(@PathVariable Long id) {
        return Result.buildSuc(service.detail(id));
    }

    @PostMapping("/{id}/reconcile")
    public Result<LakeManagedTableVO> reconcile(@PathVariable Long id) {
        return Result.buildSuc(service.reconcile(id));
    }

    @PostMapping("/{id}/retry")
    public Result<LakeManagedTableVO> retry(@PathVariable Long id) {
        return Result.buildSuc(service.retry(id));
    }

    @GetMapping("/{id}/delete-impact")
    public Result<LakeManagedTableDeleteImpactVO> deleteImpact(@PathVariable Long id) {
        return Result.buildSuc(service.deleteImpact(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @PathVariable Long id,
            @RequestBody(required = false) LakeManagedTableDeleteDTO request) {
        service.delete(id, request);
        return Result.buildSuc();
    }
}
