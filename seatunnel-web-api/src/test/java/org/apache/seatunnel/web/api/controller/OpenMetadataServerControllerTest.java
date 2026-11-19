package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.OpenMetadataServerService;
import org.apache.seatunnel.web.common.enums.UserType;
import org.apache.seatunnel.web.dao.entity.User;
import org.apache.seatunnel.web.spi.bean.dto.OpenMetadataServerConfigDTO;
import org.apache.seatunnel.web.spi.bean.vo.OpenMetadataServerConfigVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenMetadataServerControllerTest {

    @Test
    void settingsOperationsRejectNonAdminUsers() {
        OpenMetadataServerService service = mock(OpenMetadataServerService.class);
        CurrentUserProvider users = mock(CurrentUserProvider.class);
        User generalUser = new User();
        generalUser.setUserType(UserType.GENERAL_USER);
        when(users.getCurrentUser()).thenReturn(generalUser);
        OpenMetadataServerController controller = new OpenMetadataServerController(service, users);

        assertForbidden(controller::getConfig);
        assertForbidden(() -> controller.saveConfig(new OpenMetadataServerConfigDTO()));
        assertForbidden(() -> controller.testConfig(new OpenMetadataServerConfigDTO()));
        verifyNoInteractions(service);
    }

    @Test
    void adminCanReadSettings() {
        OpenMetadataServerService service = mock(OpenMetadataServerService.class);
        CurrentUserProvider users = mock(CurrentUserProvider.class);
        User adminUser = new User();
        adminUser.setUserType(UserType.ADMIN_USER);
        when(users.getCurrentUser()).thenReturn(adminUser);
        OpenMetadataServerConfigVO expected = new OpenMetadataServerConfigVO();
        when(service.getConfig()).thenReturn(expected);
        OpenMetadataServerController controller = new OpenMetadataServerController(service, users);

        assertEquals(expected, controller.getConfig().getData());
        verify(service).getConfig();
    }

    private static void assertForbidden(Runnable operation) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, operation::run);
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }
}
