package org.apache.seatunnel.web.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.seatunnel.web.api.metadata.AssetExplorationService;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationAssetDetailVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationAssetPageVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Level-by-level exploration of OpenMetadata non-relational assets. */
@RestController
@RequestMapping("/api/v1/data-exploration")
@Tag(name = "DATA_EXPLORATION_TAG")
public class AssetExplorationController {

    @Resource
    private AssetExplorationService assetExplorationService;

    @GetMapping("/assets")
    @Operation(
            summary = "listDataExplorationAssets",
            description = "List one level of topics, indexes, containers, directories, files, API collections or endpoints")
    public Result<DataExplorationAssetPageVO> assets(
            @RequestParam("dataSourceId") Long dataSourceId,
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam(value = "parentType", required = false) String parentType,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "50") int pageSize,
            @RequestParam(value = "search", required = false) String search) {
        return Result.buildSuc(assetExplorationService.listAssets(
                dataSourceId, parentId, parentType, pageNo, pageSize, search));
    }

    @GetMapping("/assets/{assetId}")
    @Operation(
            summary = "getDataExplorationAsset",
            description = "Read one non-relational asset with its schema, sample payload and properties")
    public Result<DataExplorationAssetDetailVO> asset(
            @PathVariable("assetId") String assetId,
            @RequestParam("dataSourceId") Long dataSourceId,
            @RequestParam("assetType") String assetType) {
        return Result.buildSuc(
                assetExplorationService.getAsset(dataSourceId, assetId, assetType));
    }
}
