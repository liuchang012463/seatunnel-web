package org.apache.seatunnel.web.api.log;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

/**
 * Creates a Spring AI client only when all three LLM properties are present.
 * The endpoint is OpenAI-compatible, so the configured base URL can point at
 * the user's selected provider.
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
@Slf4j
public class JobLogAiConfiguration {

    @Bean
    @ConditionalOnExpression(
            "'${spring.ai.llm.api-key:}' != '' && '${spring.ai.llm.base-url:}' != '' && '${spring.ai.llm.chat.options.model:}' != ''")
    public ChatClient jobLogChatClient(LlmProperties properties) {
        String apiKey = properties.getApiKey();
        String baseUrl = properties.getBaseUrl().trim().replaceAll("/+$", "");
        URI endpoint = URI.create(baseUrl);
        // Keep the legacy v1 default unless the provider's base URL already supplies an API version.
        String completionsPath = endpoint.getPath().matches(".*/v[0-9]+")
                ? "/chat/completions" : "/v1/chat/completions";
        String model = properties.getModel();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        RestClient.Builder restClient = RestClient.builder();
        boolean cleartext = "http".equalsIgnoreCase(endpoint.getScheme());
        if (cleartext) {
            // Some Uvicorn-based model servers lose the POST body when the JDK attempts an h2c upgrade.
            HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            restClient.requestFactory(new JdkClientHttpRequestFactory(httpClient));
        }
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .completionsPath(completionsPath)
                .headers(headers)
                .restClientBuilder(restClient)
                .build();
        log.info("LLM chat client configured: host={}, completionsPath={}, syncHttpVersion={}",
                endpoint.getHost(), completionsPath, cleartext ? "HTTP/1.1" : "default");
        return ChatClient.create(OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build());
    }
}
