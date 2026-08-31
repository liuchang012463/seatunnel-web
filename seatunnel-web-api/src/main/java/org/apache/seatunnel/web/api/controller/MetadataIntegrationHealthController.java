package org.apache.seatunnel.web.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationHealthService;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.MetadataIntegrationHealthVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator health endpoint; ordinary product pages do not need OM credentials. */
@RestController
@RequestMapping("/api/v1/metadata-integration")
@Tag(name = "METADATA_INTEGRATION_TAG")
public class MetadataIntegrationHealthController {

    @Resource
    private MetadataIntegrationHealthService metadataIntegrationHealthService;

    @GetMapping("/health")
    @Operation(summary = "getMetadataIntegrationHealth", description = "Read fixed OpenMetadata integration health")
    public Result<MetadataIntegrationHealthVO> health() {
        return Result.buildSuc(metadataIntegrationHealthService.health());
    }
}
