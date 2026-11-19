package org.apache.seatunnel.web.api.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.seatunnel.web.api.log.JobLogFaultDiagnosisService;
import org.apache.seatunnel.web.api.log.JobLogService;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobLogControllerTest {

    @Mock
    private JobLogService jobLogService;

    @Mock
    private JobLogFaultDiagnosisService jobLogFaultDiagnosisService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @InjectMocks
    private JobLogController controller;

    @Test
    void requiresAuthenticatedUserBeforeReadingTail() {
        when(currentUserProvider.requireCurrentUser())
                .thenThrow(new IllegalStateException("login required"));

        assertThrows(IllegalStateException.class,
                () -> controller.tail("BATCH", 1L, null, null));

        verify(currentUserProvider).requireCurrentUser();
        verifyNoInteractions(jobLogService);
    }

    @Test
    void requiresAuthenticatedUserBeforeStreamingDownload() throws Exception {
        when(currentUserProvider.requireCurrentUser())
                .thenThrow(new IllegalStateException("login required"));
        HttpServletResponse response = org.mockito.Mockito.mock(HttpServletResponse.class);

        assertThrows(IllegalStateException.class,
                () -> controller.download("BATCH", 1L, response));

        verify(currentUserProvider).requireCurrentUser();
        verifyNoInteractions(jobLogService);
    }
}
