package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.lake.query.LakeReadOnlyQueryResultVO;
import org.apache.seatunnel.web.api.lake.query.LakeReadOnlyQueryPreviewVO;
import org.apache.seatunnel.web.api.lake.query.LakeQueryColumnOptionVO;
import org.apache.seatunnel.web.api.service.LakeLogicalCatalogQueryService;
import org.apache.seatunnel.web.spi.bean.dto.LakeJoinQueryDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeSingleTableQueryDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Structured, bounded read-only query endpoints; arbitrary SQL is not accepted. */
@RestController
@RequestMapping("/api/v1/lake/logical/query")
public class LakeLogicalCatalogQueryController {

    private final LakeLogicalCatalogQueryService service;

    @Autowired
    public LakeLogicalCatalogQueryController(LakeLogicalCatalogQueryService service) {
        this.service = service;
    }

    @PostMapping("/catalogs/{id}/single")
    public Result<LakeReadOnlyQueryResultVO> single(
            @PathVariable("id") Long id,
            @RequestBody LakeSingleTableQueryDTO request) {
        return Result.buildSuc(service.single(id, request));
    }

    /** API-baseline alias; the catalog binding is explicit and never inferred from the body. */
    @PostMapping("/single-table")
    public Result<LakeReadOnlyQueryResultVO> singleTable(
            @RequestParam(value = "catalogBindingId", required = false) Long catalogBindingId,
            @RequestBody LakeSingleTableQueryDTO request) {
        Long resolvedId = catalogBindingId == null && request != null
                ? request.catalogBindingId() : catalogBindingId;
        return Result.buildSuc(service.single(resolvedId, request));
    }

    /** Generates a safe preview without executing the query or writing an audit row. */
    @PostMapping("/single-table/preview")
    public Result<LakeReadOnlyQueryPreviewVO> singleTablePreview(
            @RequestParam(value = "catalogBindingId", required = false) Long catalogBindingId,
            @RequestBody LakeSingleTableQueryDTO request) {
        Long resolvedId = catalogBindingId == null && request != null
                ? request.catalogBindingId() : catalogBindingId;
        return Result.buildSuc(service.previewSingle(resolvedId, request));
    }

    @PostMapping("/join")
    public Result<LakeReadOnlyQueryResultVO> join(@RequestBody LakeJoinQueryDTO request) {
        return Result.buildSuc(service.join(request));
    }

    /** Cancels a currently running structured query; unknown ids are harmless. */
    @PostMapping("/cancel/{queryId}")
    public Result<Boolean> cancel(@PathVariable("queryId") String queryId) {
        return Result.buildSuc(service.cancel(queryId));
    }

    @PostMapping("/join/preview")
    public Result<LakeReadOnlyQueryPreviewVO> joinPreview(@RequestBody LakeJoinQueryDTO request) {
        return Result.buildSuc(service.previewJoin(request));
    }

    @GetMapping("/catalogs/{id}/databases")
    public Result<List<String>> databases(@PathVariable("id") Long id) {
        return Result.buildSuc(service.databases(id));
    }

    @GetMapping("/catalogs/{id}/tables")
    public Result<List<String>> tables(
            @PathVariable("id") Long id,
            @RequestParam("database") String database) {
        return Result.buildSuc(service.tables(id, database));
    }

    @GetMapping("/catalogs/{id}/columns")
    public Result<List<LakeQueryColumnOptionVO>> columns(
            @PathVariable("id") Long id,
            @RequestParam("database") String database,
            @RequestParam("table") String table) {
        return Result.buildSuc(service.columns(id, database, table));
    }
}
