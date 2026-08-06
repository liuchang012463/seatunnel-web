package org.apache.seatunnel.web.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.seatunnel.web.api.log.JobLogSearchResult;
import org.apache.seatunnel.web.api.log.JobLogAnalysisResult;
import org.apache.seatunnel.web.api.log.JobLogReplayResult;
import org.apache.seatunnel.web.api.log.JobLogFaultDiagnosisResult;
import org.apache.seatunnel.web.api.log.JobLogFaultDiagnosisService;
import org.apache.seatunnel.web.api.log.JobLogDiagnosisStreamEvent;
import org.apache.seatunnel.web.api.log.JobLogService;
import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1/job-log")
@Tag(name = "JOB_LOG_TAG")
public class JobLogController {

    @Resource
    private JobLogService jobLogService;

    @Resource
    private JobLogFaultDiagnosisService jobLogFaultDiagnosisService;

    @GetMapping("/{jobMode}/{instanceId}/search")
    @Operation(summary = "searchJobInstanceLog", description = "按条件检索任务完整日志")
    @Parameters({
            @Parameter(name = "jobMode", description = "BATCH or STREAMING", required = true),
            @Parameter(name = "instanceId", description = "任务实例 ID", required = true),
            @Parameter(name = "keyword", description = "不区分大小写的关键字"),
            @Parameter(name = "level", description = "日志级别，例如 ERROR"),
            @Parameter(name = "source", description = "日志来源，例如 WEB 或 ENGINE"),
            @Parameter(name = "category", description = "日志分类，例如 EXECUTION_FLOW"),
            @Parameter(name = "page", description = "页码，从 1 开始"),
            @Parameter(name = "pageSize", description = "页大小，最大 500")
    })
    public Result<JobLogSearchResult> search(
            @PathVariable("jobMode") String jobMode,
            @PathVariable("instanceId") Long instanceId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "level", required = false) String level,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.buildSuc(jobLogService.search(
                instanceId,
                parseMode(jobMode),
                keyword,
                level,
                source,
                category,
                page,
                pageSize
        ));
    }

    @GetMapping("/{jobMode}/{instanceId}/analysis")
    @Operation(summary = "analyzeJobInstanceLog", description = "按规则提取任务日志中的操作、数据、流程和时序记录")
    public Result<JobLogAnalysisResult> analysis(
            @PathVariable("jobMode") String jobMode,
            @PathVariable("instanceId") Long instanceId) {
        return Result.buildSuc(jobLogService.analyze(instanceId, parseMode(jobMode)));
    }

    @GetMapping("/{jobMode}/{instanceId}/replay")
    @Operation(summary = "replayJobInstanceLog", description = "返回可视化操作回放所需的有序日志步骤")
    public Result<JobLogReplayResult> replay(
            @PathVariable("jobMode") String jobMode,
            @PathVariable("instanceId") Long instanceId) {
        return Result.buildSuc(jobLogService.replay(instanceId, parseMode(jobMode)));
    }

    @GetMapping("/{jobMode}/{instanceId}/diagnosis")
    @Operation(summary = "diagnoseJobInstanceLog", description = "分析日志、数据快照和执行流程，定位任务故障类型及原因")
    public Result<JobLogFaultDiagnosisResult> diagnosis(
            @PathVariable("jobMode") String jobMode,
            @PathVariable("instanceId") Long instanceId) {
        return Result.buildSuc(jobLogFaultDiagnosisService.diagnose(instanceId, parseMode(jobMode)));
    }

    @GetMapping(value = "/{jobMode}/{instanceId}/diagnosis/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "streamDiagnoseJobInstanceLog", description = "流式分析失败任务日志并定位故障类型")
    public Flux<ServerSentEvent<JobLogDiagnosisStreamEvent>> diagnosisStream(
            @PathVariable("jobMode") String jobMode,
            @PathVariable("instanceId") Long instanceId) {
        return jobLogFaultDiagnosisService.streamDiagnose(instanceId, parseMode(jobMode))
                .map(event -> ServerSentEvent.<JobLogDiagnosisStreamEvent>builder()
                        .event(event.type())
                        .data(event)
                        .build());
    }

    private JobMode parseMode(String value) {
        try {
            return JobMode.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "jobMode");
        }
    }
}
