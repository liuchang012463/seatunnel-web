package org.apache.seatunnel.web.api.controller;

import jakarta.annotation.Resource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.seatunnel.web.api.metadata.DataExplorationExportService;
import org.apache.seatunnel.web.spi.bean.dto.DataInventoryFilterDTO;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

/** Streaming normalized XLSX export for the existing exploration facade. */
@RestController
@RequestMapping("/api/v1/data-exploration")
@Tag(name = "DATA_EXPLORATION_TAG")
public class DataExplorationExportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    @Resource
    private DataExplorationExportService dataExplorationExportService;

    @PostMapping(value = "/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @Operation(summary = "exportDataExploration", description = "Stream normalized data-exploration XLSX")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestBody(required = false) DataInventoryFilterDTO filter) {
        StreamingResponseBody body = output -> dataExplorationExportService.write(filter, output);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(XLSX);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("data-exploration.xlsx", StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
