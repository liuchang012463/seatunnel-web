package org.apache.seatunnel.web.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.seatunnel.web.api.service.OpenMetadataServerService;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.common.enums.UserType;
import org.apache.seatunnel.web.dao.entity.User;
import org.apache.seatunnel.web.spi.bean.dto.OpenMetadataServerConfigDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.OpenMetadataServerConfigVO;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Tag(name = "OPENMETADATA_SERVER_TAG")
@RequestMapping("/api/v1/metadata/server")
public class OpenMetadataServerController {

    private final OpenMetadataServerService openMetadataServerService;

    private final CurrentUserProvider currentUserProvider;

    public OpenMetadataServerController(
            OpenMetadataServerService openMetadataServerService,
            CurrentUserProvider currentUserProvider) {
        this.openMetadataServerService = openMetadataServerService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    @Operation(summary = "getOpenMetadataServerConfig")
    public Result<OpenMetadataServerConfigVO> getConfig() {
        requireAdmin();
        return Result.buildSuc(openMetadataServerService.getConfig());
    }

    @PutMapping
    @Operation(summary = "saveOpenMetadataServerConfig")
    public Result<OpenMetadataServerConfigVO> saveConfig(
            @RequestBody OpenMetadataServerConfigDTO request) {
        requireAdmin();
        return Result.buildSuc(openMetadataServerService.saveConfig(request));
    }

    @PostMapping("/connect-test")
    @Operation(summary = "testOpenMetadataServerConfig")
    public Result<OpenMetadataServerConfigVO> testConfig(
            @RequestBody OpenMetadataServerConfigDTO request) {
        requireAdmin();
        return Result.buildSuc(openMetadataServerService.testConfig(request));
    }

    private void requireAdmin() {
        User user = currentUserProvider.getCurrentUser();
        if (user == null || user.getUserType() != UserType.ADMIN_USER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "管理员权限不足");
        }
    }
}
