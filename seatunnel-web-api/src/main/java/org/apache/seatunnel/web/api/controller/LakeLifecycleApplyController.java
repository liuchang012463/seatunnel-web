package org.apache.seatunnel.web.api.controller;

import jakarta.validation.Valid;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleValidateVO;
import org.apache.seatunnel.web.api.service.LakeLifecycleApplyService;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleApplyDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleRetentionUpdateDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit lifecycle policy application and retention update endpoints. */
@RestController
@RequestMapping("/api/v1/lake/lifecycle")
public class LakeLifecycleApplyController {

    private final LakeLifecycleApplyService service;

    @Autowired
    public LakeLifecycleApplyController(LakeLifecycleApplyService service) {
        this.service = service;
    }

    @PostMapping("/apply")
    public Result<LakeLifecycleValidateVO> apply(
            @Valid @RequestBody LakeLifecycleApplyDTO request) {
        return Result.buildSuc(service.apply(request));
    }

    @PutMapping("/tables/{mappingId}/retention")
    public Result<LakeLifecycleValidateVO> update(
            @PathVariable Long mappingId,
            @Valid @RequestBody LakeLifecycleRetentionUpdateDTO request) {
        return Result.buildSuc(service.update(mappingId, request));
    }
}
