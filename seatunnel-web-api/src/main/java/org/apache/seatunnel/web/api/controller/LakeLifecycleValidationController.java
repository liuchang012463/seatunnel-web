package org.apache.seatunnel.web.api.controller;

import jakarta.validation.Valid;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleValidateVO;
import org.apache.seatunnel.web.api.service.LakeLifecycleValidationService;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleValidateDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit lifecycle validation and cached detail endpoints. */
@RestController
@RequestMapping("/api/v1/lake/lifecycle")
public class LakeLifecycleValidationController {

    private final LakeLifecycleValidationService service;

    @Autowired
    public LakeLifecycleValidationController(LakeLifecycleValidationService service) {
        this.service = service;
    }

    /** Read-through validation is explicit and therefore POST-only. */
    @PostMapping("/validate")
    public Result<LakeLifecycleValidateVO> validate(
            @Valid @RequestBody LakeLifecycleValidateDTO request) {
        return Result.buildSuc(service.validate(request));
    }

    /** Cached detail never calls Doris or changes observation state. */
    @GetMapping("/tables/{mappingId}")
    public Result<LakeLifecycleValidateVO> detail(@PathVariable Long mappingId) {
        return Result.buildSuc(service.detail(mappingId));
    }
}
