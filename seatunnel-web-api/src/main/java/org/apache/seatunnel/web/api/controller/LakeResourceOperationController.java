package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.lake.operation.LakeResourceOperationVO;
import org.apache.seatunnel.web.api.service.LakeResourceOperationService;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only operation journal endpoint for all lake resource detail pages. */
@RestController
@RequestMapping("/api/v1/lake/operations")
public class LakeResourceOperationController {

    private final LakeResourceOperationService service;

    @Autowired
    public LakeResourceOperationController(LakeResourceOperationService service) {
        this.service = service;
    }

    @GetMapping("/{resourceType}/{resourceId}")
    public Result<List<LakeResourceOperationVO>> list(
            @PathVariable("resourceType") String resourceType,
            @PathVariable("resourceId") Long resourceId) {
        return Result.buildSuc(service.list(resourceType, resourceId));
    }
}
