package org.apache.seatunnel.web.engine.client;

import org.apache.seatunnel.web.engine.client.rest.SeaTunnelClientResolver;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;

class SeaTunnelRestClientUploadTest {

    @Test
    void uploadsUtf8ConfigWithMoreThanOneHundredTablesAndEngineThreeSavepointId() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        SeaTunnelClientResolver resolver = mock(SeaTunnelClientResolver.class);
        when(resolver.resolveBaseApiUrl(7L)).thenReturn("http://engine.test");
        when(resolver.resolveAuth(anyLong())).thenReturn(null);
        SeaTunnelRestClient client = new SeaTunnelRestClient(restTemplate, resolver);

        StringBuilder config = new StringBuilder("source {\n  table_path = [\n");
        for (int i = 1; i <= 101; i++) {
            config.append("    \"业务库.订单表_").append(i).append("\",\n");
        }
        config.append("  ]\n}\n");
        server.expect(requestTo("http://engine.test/submit-job/upload?jobId=601"
                        + "&isStartWithSavePoint=true&restoreMode=SAVEPOINT&restoreSourceJobId=601"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(containsString("业务库.订单表_101")))
                .andRespond(withSuccess("{\"jobId\":\"601\"}", MediaType.APPLICATION_JSON));

        Map<?, ?> response = client.submitJobUpload(
                7L,
                config.toString().getBytes(StandardCharsets.UTF_8),
                "streaming-job-601.conf",
                "601",
                null,
                true,
                "601");

        assertEquals("601", response.get("jobId"));
        server.verify();
    }
}
