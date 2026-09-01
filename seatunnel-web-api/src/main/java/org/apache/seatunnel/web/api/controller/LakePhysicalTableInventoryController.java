package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.lake.inventory.LakePhysicalTableInventoryVO;
import org.apache.seatunnel.web.api.service.LakePhysicalTableInventoryService;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only registered/discovered table inventory for one ODS database. */
@RestController
@RequestMapping("/api/v1/lake/physical/databases")
public class LakePhysicalTableInventoryController {

    private final LakePhysicalTableInventoryService service;

    @Autowired
    public LakePhysicalTableInventoryController(LakePhysicalTableInventoryService service) {
        this.service = service;
    }

    @GetMapping("/{id}/inventory")
    public Result<LakePhysicalTableInventoryVO> inventory(@PathVariable("id") Long id) {
        return Result.buildSuc(service.inventory(id));
    }
}
