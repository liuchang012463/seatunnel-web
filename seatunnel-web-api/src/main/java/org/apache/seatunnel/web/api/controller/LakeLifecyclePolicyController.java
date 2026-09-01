package org.apache.seatunnel.web.api.controller;

import jakarta.validation.Valid;
import org.apache.seatunnel.web.api.service.LakeLifecyclePolicyService;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyDisableDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyPageDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyUpdateDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.LakeLifecyclePolicyVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** v1.4 lifecycle policy endpoints. */
@RestController
@RequestMapping("/api/v1/lake/lifecycle")
public class LakeLifecyclePolicyController {

    private final LakeLifecyclePolicyService service;

    @Autowired
    public LakeLifecyclePolicyController(LakeLifecyclePolicyService service) {
        this.service = service;
    }

    @PostMapping("/policies/page")
    public PaginationResult<LakeLifecyclePolicyVO> page(
            @Valid @RequestBody(required = false) LakeLifecyclePolicyPageDTO request) {
        return service.page(request);
    }

    @PostMapping("/policies")
    public Result<LakeLifecyclePolicyVO> create(
            @Valid @RequestBody LakeLifecyclePolicyCreateDTO request) {
        return Result.buildSuc(service.create(request));
    }

    @PutMapping("/policies/{id}")
    public Result<LakeLifecyclePolicyVO> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody LakeLifecyclePolicyUpdateDTO request) {
        return Result.buildSuc(service.update(id, request));
    }

    @PostMapping("/policies/{id}/disable")
    public Result<LakeLifecyclePolicyVO> disable(
            @PathVariable("id") Long id,
            @Valid @RequestBody LakeLifecyclePolicyDisableDTO request) {
        return Result.buildSuc(service.disable(id, request));
    }
}
