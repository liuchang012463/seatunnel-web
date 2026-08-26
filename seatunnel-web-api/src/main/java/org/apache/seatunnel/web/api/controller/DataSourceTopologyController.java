package org.apache.seatunnel.web.api.controller;

import jakarta.annotation.Resource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.seatunnel.web.api.metadata.DataSourceTopologyService;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceTopologyNodeVO;
import org.apache.seatunnel.web.spi.enums.DataSourceTopologyNodeType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Lazy Unit -> BusinessSystem -> DataSource -> OpenMetadata topology APIs. */
@RestController
@RequestMapping("/api/v1/data-source-topology")
@Tag(name = "DATA_SOURCE_TOPOLOGY_TAG")
public class DataSourceTopologyController {

    @Resource
    private DataSourceTopologyService dataSourceTopologyService;

    @GetMapping("/tree")
    @Operation(summary = "getDataSourceTopologyTree", description = "Read the shallow local topology tree")
    public Result<List<DataSourceTopologyNodeVO>> tree(
            @RequestParam(value = "unitId", required = false) Long unitId,
            @RequestParam(value = "businessSystemId", required = false) Long businessSystemId,
            @RequestParam(value = "dataSourceId", required = false) Long dataSourceId) {
        return Result.buildSuc(dataSourceTopologyService.tree(unitId, businessSystemId, dataSourceId));
    }

    @GetMapping("/children")
    @Operation(summary = "getDataSourceTopologyChildren", description = "Load one topology level lazily")
    public Result<List<DataSourceTopologyNodeVO>> children(
            @RequestParam("nodeType") DataSourceTopologyNodeType nodeType,
            @RequestParam("nodeId") String nodeId) {
        return Result.buildSuc(dataSourceTopologyService.children(nodeType, nodeId));
    }
}
