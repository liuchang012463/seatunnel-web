package org.apache.seatunnel.web.api.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.common.enums.JobMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobLogFaultDiagnosisServiceTest {

    @Mock
    private JobLogService jobLogService;

    @Mock
    private ObjectProvider<ChatClient> chatClientProvider;

    @Test
    void fallsBackToDataSourceClassificationWhenAiIsUnavailable() {
        Long instanceId = 11L;
        JobMode mode = JobMode.BATCH;
        JobLogContext context = new JobLogContext(
                instanceId,
                21L,
                null,
                null,
                mode,
                "source { plugin = Jdbc password = secret }",
                null,
                "FAILED"
        );
        JobLogEntry error = new JobLogEntry(
                1L,
                2L,
                "2026-08-05 10:00:01",
                "ERROR",
                "WEB",
                JobLogParser.CATEGORY_ERROR,
                "ERROR",
                "connection refused while reading JDBC source",
                "[2026-08-05 10:00:01] [ERROR] connection refused while reading JDBC source",
                1000L
        );
        JobLogAnalysisResult analysis = new JobLogAnalysisResult(
                instanceId,
                mode.name(),
                2,
                1,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(error),
                List.of()
        );
        when(jobLogService.resolve(instanceId, mode)).thenReturn(context);
        when(jobLogService.analyze(instanceId, mode)).thenReturn(analysis);
        when(chatClientProvider.getIfAvailable()).thenReturn(null);

        JobLogFaultDiagnosisResult result = new JobLogFaultDiagnosisService(
                jobLogService,
                new ObjectMapper(),
                chatClientProvider
        ).diagnose(instanceId, mode);

        assertFalse(result.aiUsed());
        assertEquals("DATA_SOURCE", result.faultType());
        assertEquals("数据源", result.faultTypeLabel());
        assertEquals("RULE", result.provider());
    }
}
