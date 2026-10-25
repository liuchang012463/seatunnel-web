package org.apache.seatunnel.web.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.apache.seatunnel.web.api.service.OfflineImportTaskService;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskQueryDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.OfflineImportTaskVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP API for DMP/SQL offline import tasks. */
@RestController
@RequestMapping("/api/v1/offline-import-tasks")
@Tag(name = "OFFLINE_IMPORT_TASK_TAG")
public class OfflineImportTaskController {

    @Resource
    private OfflineImportTaskService offlineImportTaskService;

    @GetMapping("/page")
    @Operation(summary = "pageOfflineImportTasks", description = "分页查询 DMP/SQL 离线导入任务")
    public PaginationResult<OfflineImportTaskVO> page(@ModelAttribute OfflineImportTaskQueryDTO query) {
        return offlineImportTaskService.page(query);
    }

    @PostMapping
    @Operation(summary = "createOfflineImportTask", description = "创建 DMP/SQL 离线导入任务")
    public Result<OfflineImportTaskVO> create(@Valid @RequestBody OfflineImportTaskCreateDTO dto) {
        return Result.buildSuc(offlineImportTaskService.create(dto));
    }

    @GetMapping("/{id}")
    @Operation(summary = "getOfflineImportTask", description = "获取 DMP/SQL 离线导入任务详情")
    public Result<OfflineImportTaskVO> get(@PathVariable("id") Long id) {
        return Result.buildSuc(offlineImportTaskService.get(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "deleteOfflineImportTask", description = "删除 DMP/SQL 离线导入任务")
    public Result<Boolean> delete(@PathVariable("id") Long id) {
        offlineImportTaskService.delete(id);
        return Result.buildSuc(true);
    }

    @PostMapping("/{id}/execute")
    @Operation(summary = "executeOfflineImportTask", description = "执行 DMP/SQL 离线导入任务")
    public Result<Boolean> execute(@PathVariable("id") Long id) {
        offlineImportTaskService.execute(id);
        return Result.buildSuc(true);
    }
}
