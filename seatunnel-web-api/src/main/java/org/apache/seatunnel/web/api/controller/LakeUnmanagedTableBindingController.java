package org.apache.seatunnel.web.api.controller;

import jakarta.validation.Valid;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTableVO;
import org.apache.seatunnel.web.api.service.LakeUnmanagedTableBindingService;
import org.apache.seatunnel.web.spi.bean.dto.LakeUnmanagedTableBindDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit, POST/DELETE-only binding endpoints for existing Doris tables. */
@RestController
@RequestMapping("/api/v1/lake/physical/unmanaged")
public class LakeUnmanagedTableBindingController {

    private final LakeUnmanagedTableBindingService service;

    @Autowired
    public LakeUnmanagedTableBindingController(LakeUnmanagedTableBindingService service) {
        this.service = service;
    }

    @PostMapping("/bind")
    public Result<LakeManagedTableVO> bind(
            @Valid @RequestBody LakeUnmanagedTableBindDTO request) {
        return Result.buildSuc(service.bind(request));
    }

    @DeleteMapping("/{id}/binding")
    public Result<LakeManagedTableVO> unbind(@PathVariable("id") Long id) {
        return Result.buildSuc(service.unbind(id));
    }
}
