package org.apache.seatunnel.web.api.log;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates a Spring AI client only when all three IDEA-provided environment
 * variables are present. The endpoint is OpenAI-compatible, so the configured
 * base URL can point at the user's selected provider.
 */
@Configuration
public class JobLogAiConfiguration {

    @Bean
    @ConditionalOnExpression("'${SPRING_AI_API_KEY:}' != '' && '${SPRING_AI_BASE_URL:}' != '' && '${SPRING_AI_LLM_MODEL:}' != ''")
    public ChatClient jobLogChatClient(
            @Value("${SPRING_AI_API_KEY:}") String apiKey,
            @Value("${SPRING_AI_BASE_URL:}") String baseUrl,
            @Value("${SPRING_AI_LLM_MODEL:}") String model) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .build();
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        return ChatClient.create(OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build());
    }
}
