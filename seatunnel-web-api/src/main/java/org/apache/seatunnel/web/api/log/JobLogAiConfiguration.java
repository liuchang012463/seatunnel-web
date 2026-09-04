package org.apache.seatunnel.web.api.log;

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

import java.util.List;

/**
 * Creates a Spring AI client only when all three LLM properties are present.
 * The endpoint is OpenAI-compatible, so the configured base URL can point at
 * the user's selected provider.
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class JobLogAiConfiguration {

    @Bean
    @ConditionalOnExpression(
            "'${spring.ai.llm.api-key:}' != '' && '${spring.ai.llm.base-url:}' != '' && '${spring.ai.llm.chat.options.model:}' != ''")
    public ChatClient jobLogChatClient(LlmProperties properties) {
        String apiKey = properties.getApiKey();
        String baseUrl = properties.getBaseUrl();
        String model = properties.getModel();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .headers(headers)
                .build();
        return ChatClient.create(OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build());
    }
}
