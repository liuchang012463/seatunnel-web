package org.apache.seatunnel.web.api.controller;

import jakarta.validation.Valid;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleRetentionPreviewVO;
import org.apache.seatunnel.web.api.service.LakeLifecycleRetentionPreviewService;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleRetentionPreviewDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit read-through retention impact preview endpoint. */
@RestController
@RequestMapping("/api/v1/lake/lifecycle")
public class LakeLifecycleRetentionPreviewController {

    private final LakeLifecycleRetentionPreviewService service;

    @Autowired
    public LakeLifecycleRetentionPreviewController(
            LakeLifecycleRetentionPreviewService service) {
        this.service = service;
    }

    @PostMapping("/tables/{mappingId}/retention/preview")
    public Result<LakeLifecycleRetentionPreviewVO> preview(
            @PathVariable Long mappingId,
            @Valid @RequestBody LakeLifecycleRetentionPreviewDTO request) {
        return Result.buildSuc(service.preview(mappingId, request));
    }
}
