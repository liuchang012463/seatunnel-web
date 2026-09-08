package org.apache.seatunnel.web.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.seatunnel.web.api.service.OpenMetadataServerService;
import org.apache.seatunnel.web.spi.bean.dto.OpenMetadataServerConfigDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.OpenMetadataServerConfigVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "OPENMETADATA_SERVER_TAG")
@RequestMapping("/api/v1/metadata/server")
public class OpenMetadataServerController {

    @Resource
    private OpenMetadataServerService openMetadataServerService;

    @GetMapping
    @Operation(summary = "getOpenMetadataServerConfig")
    public Result<OpenMetadataServerConfigVO> getConfig() {
        return Result.buildSuc(openMetadataServerService.getConfig());
    }

    @PutMapping
    @Operation(summary = "saveOpenMetadataServerConfig")
    public Result<OpenMetadataServerConfigVO> saveConfig(
            @RequestBody OpenMetadataServerConfigDTO request) {
        return Result.buildSuc(openMetadataServerService.saveConfig(request));
    }

    @PostMapping("/connect-test")
    @Operation(summary = "testOpenMetadataServerConfig")
    public Result<OpenMetadataServerConfigVO> testConfig(
            @RequestBody OpenMetadataServerConfigDTO request) {
        return Result.buildSuc(openMetadataServerService.testConfig(request));
    }
}
