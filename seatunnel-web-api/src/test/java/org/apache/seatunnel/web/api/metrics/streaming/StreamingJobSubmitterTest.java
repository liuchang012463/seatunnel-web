package org.apache.seatunnel.web.api.metrics.streaming;

import org.apache.seatunnel.web.api.metrics.JobConfigFileService;
import org.apache.seatunnel.web.common.enums.JobSubmitStage;
import org.apache.seatunnel.web.common.exception.JobSubmitException;
import org.apache.seatunnel.web.engine.client.driver.EngineDriverJarPublisher;
import org.apache.seatunnel.web.engine.client.exceptions.SeaTunnelClientException;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamingJobSubmitterTest {

    @TempDir
    Path tempDir;

    @Test
    void readTimeoutKeepsStreamingSubmissionOutcomeUnknown() {
        StreamingJobResultHandler resultHandler = mock(StreamingJobResultHandler.class);
        SeaTunnelRestClient restClient = mock(SeaTunnelRestClient.class);
        doThrow(new SeaTunnelClientException("upload timed out", -1, "", new IOException("Read timed out")))
                .when(restClient).submitJobUpload(
                        eq(2L), any(byte[].class), anyString(), eq("301"), eq((String) null),
                        eq(false), eq((String) null));
        StreamingJobSubmitter submitter = submitter(restClient, resultHandler);

        JobSubmitException error = assertThrows(
                JobSubmitException.class,
                () -> submitter.submit(instance()));

        assertTrue(error.isOutcomeUnknown());
        assertTrue(error.getCause().getCause().getMessage().contains("Read timed out"));
        verify(resultHandler, never()).handleFailure(anyLong(), any(Throwable.class));
    }

    @Test
    void cancelingPreflightSkipsEngineUpload() {
        StreamingJobResultHandler resultHandler = mock(StreamingJobResultHandler.class);
        SeaTunnelRestClient restClient = mock(SeaTunnelRestClient.class);
        when(resultHandler.skipSubmissionIfNotStarting(301L, "301")).thenReturn(true);
        StreamingJobSubmitter submitter = submitter(restClient, resultHandler);

        assertDoesNotThrow(() -> submitter.submit(instance()));

        verify(restClient, never()).submitJobUpload(
                eq(2L), any(byte[].class), anyString(), eq("301"), eq((String) null),
                eq(false), eq((String) null));
    }

    @Test
    void lateSubmitResponseAfterPauseImmediatelyRetriesStopWithReservedId() {
        StreamingJobResultHandler resultHandler = mock(StreamingJobResultHandler.class);
        SeaTunnelRestClient restClient = mock(SeaTunnelRestClient.class);
        when(restClient.submitJobUpload(
                eq(2L), any(byte[].class), anyString(), eq("301"), eq((String) null),
                eq(false), eq((String) null)))
                .thenReturn(Map.of("jobId", "301"));
        when(resultHandler.updateEngineId(301L, "301")).thenReturn(false);
        when(resultHandler.isCancellationRequested(301L, "301")).thenReturn(true);
        StreamingJobSubmitter submitter = submitter(restClient, resultHandler);

        assertDoesNotThrow(() -> submitter.submit(instance()));

        verify(restClient).stopJob(2L, "301", false);
        verify(resultHandler, never()).isEngineJobTracked(anyLong(), anyString());
    }

    @Test
    void engineRejectionIsRecordedAsDefiniteFailure() {
        StreamingJobResultHandler resultHandler = mock(StreamingJobResultHandler.class);
        SeaTunnelRestClient restClient = mock(SeaTunnelRestClient.class);
        doThrow(new SeaTunnelClientException("upload rejected", 400, "invalid config", null))
                .when(restClient).submitJobUpload(
                        eq(2L), any(byte[].class), anyString(), eq("301"), eq((String) null),
                        eq(false), eq((String) null));
        StreamingJobSubmitter submitter = submitter(restClient, resultHandler);

        JobSubmitException error = assertThrows(
                JobSubmitException.class,
                () -> submitter.submit(instance()));

        assertFalse(error.isOutcomeUnknown());
        verify(resultHandler).handleFailure(eq(301L), any(Throwable.class));
    }

    @Test
    void successfulEngineThreeSavepointResumeDoesNotStopTheReusedEngineJobId() {
        StreamingJobResultHandler resultHandler = mock(StreamingJobResultHandler.class);
        SeaTunnelRestClient restClient = mock(SeaTunnelRestClient.class);
        when(resultHandler.updateEngineId(301L, "engine-501")).thenReturn(true);
        when(restClient.submitJobUpload(
                eq(2L), any(byte[].class), anyString(), eq("engine-501"), eq((String) null),
                eq(true), eq("engine-501")))
                .thenReturn(Map.of("jobId", "engine-501"));
        StreamingJobSubmitter submitter = submitter(restClient, resultHandler);

        JobInstanceVO instance = instance();
        instance.setEngineJobId("engine-501");

        assertDoesNotThrow(() -> submitter.submitFromSavepoint(instance, "engine-501"));

        verify(resultHandler).updateEngineId(301L, "engine-501");
        verify(restClient, never()).stopJob(anyLong(), anyString(), anyBoolean());
    }

    private StreamingJobSubmitter submitter(SeaTunnelRestClient restClient,
                                            StreamingJobResultHandler resultHandler) {
        JobConfigFileService configFileService = mock(JobConfigFileService.class);
        when(configFileService.writeConfig(eq(301L), anyString())).thenReturn(tempDir.resolve("job.conf").toString());
        EngineDriverJarPublisher driverPublisher = mock(EngineDriverJarPublisher.class);
        when(driverPublisher.publish(eq(2L), anyString())).thenReturn("source {}");
        return new StreamingJobSubmitter(
                configFileService,
                restClient,
                driverPublisher,
                mock(StreamingJobMetricsMonitor.class),
                mock(StreamingJobResultWatcher.class),
                resultHandler,
                Optional.empty());
    }

    private JobInstanceVO instance() {
        JobInstanceVO instance = new JobInstanceVO();
        instance.setId(301L);
        instance.setJobDefinitionId(401L);
        instance.setClientId(2L);
        instance.setEngineJobId("301");
        instance.setLogPath(tempDir.resolve("submit.log").toString());
        instance.setRuntimeConfig("source {}");
        return instance;
    }
}
