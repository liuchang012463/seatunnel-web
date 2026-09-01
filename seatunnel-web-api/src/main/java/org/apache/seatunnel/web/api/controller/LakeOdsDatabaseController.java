package org.apache.seatunnel.web.api.controller;

import jakarta.validation.Valid;
import org.apache.seatunnel.web.api.service.LakeOdsDatabaseService;
import org.apache.seatunnel.web.spi.bean.dto.LakeOdsDatabaseCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakePhysicalDataSourcePageDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.LakeOdsDatabaseVO;
import org.apache.seatunnel.web.spi.bean.vo.LakePhysicalDataSourceVO;
import org.apache.seatunnel.web.spi.bean.vo.LakePhysicalSummaryVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

/** v1.4 physical source/ODS database endpoints. */
@RestController
@RequestMapping("/api/v1/lake/physical")
public class LakeOdsDatabaseController {

    private final LakeOdsDatabaseService service;

    @Autowired
    public LakeOdsDatabaseController(LakeOdsDatabaseService service) {
        this.service = service;
    }

    @PostMapping("/datasources/page")
    public PaginationResult<LakePhysicalDataSourceVO> page(
            @RequestBody(required = false) LakePhysicalDataSourcePageDTO request) {
        return service.page(request);
    }

    @GetMapping("/summary")
    public Result<LakePhysicalSummaryVO> summary() {
        return Result.buildSuc(service.summary());
    }

    @GetMapping("/datasources/{sourceDataSourceId}")
    public Result<LakePhysicalDataSourceVO> sourceDetail(
            @PathVariable("sourceDataSourceId") Long sourceDataSourceId) {
        return Result.buildSuc(service.sourceDetail(sourceDataSourceId));
    }

    @PostMapping("/datasources/{sourceDataSourceId}/database")
    public Result<LakeOdsDatabaseVO> create(
            @PathVariable("sourceDataSourceId") Long sourceDataSourceId,
            @Valid @RequestBody LakeOdsDatabaseCreateDTO request) {
        return Result.buildSuc(service.create(sourceDataSourceId, request));
    }

    @GetMapping("/databases/{id}")
    public Result<LakeOdsDatabaseVO> detail(@PathVariable("id") Long id) {
        return Result.buildSuc(service.detail(id));
    }

    @PostMapping("/databases/{id}/retry")
    public Result<LakeOdsDatabaseVO> retry(@PathVariable("id") Long id) {
        return Result.buildSuc(service.retry(id));
    }

    @PostMapping("/databases/{id}/reconcile")
    public Result<LakeOdsDatabaseVO> reconcile(@PathVariable("id") Long id) {
        return Result.buildSuc(service.reconcile(id));
    }

    @DeleteMapping("/databases/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        service.delete(id);
        return Result.buildSuc();
    }
}
