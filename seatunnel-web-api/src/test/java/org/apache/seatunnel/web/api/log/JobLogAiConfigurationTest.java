/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.web.api.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JobLogAiConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsLlmPropertiesAndCreatesChatClientWhenConfigurationIsComplete() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.llm.api-key=test-key",
                        "spring.ai.llm.base-url=https://example.test/v1",
                        "spring.ai.llm.chat.options.model=test-model")
                .run(
                        context -> {
                            LlmProperties properties = context.getBean(LlmProperties.class);
                            assertThat(properties.getApiKey()).isEqualTo("test-key");
                            assertThat(properties.getBaseUrl()).isEqualTo("https://example.test/v1");
                            assertThat(properties.getModel()).isEqualTo("test-model");
                            assertThat(context).hasSingleBean(ChatClient.class);
                        });
    }

    @Test
    void doesNotCreateChatClientWhenConfigurationIsIncomplete() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.llm.api-key=test-key",
                        "spring.ai.llm.base-url=https://example.test/v1")
                .run(context -> assertThat(context).doesNotHaveBean(ChatClient.class));
    }

    @ParameterizedTest
    @CsvSource({
            "/, /chat/completions",
            "/gateway, /gateway/chat/completions",
            "/v1, /v1/chat/completions",
            "/api/coding/v3/, /api/coding/v3/chat/completions"
    })
    void callsTheSameEndpointForOrdinaryAndStreamingCompletions(
            String basePath, String expectedPath) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(expectedPath, exchange -> {
            try (exchange) {
                if (!"POST".equals(exchange.getRequestMethod())
                        || !expectedPath.equals(exchange.getRequestURI().getPath())) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                // A cleartext h2c upgrade can make Uvicorn-based endpoints lose the POST body.
                if (exchange.getRequestHeaders().containsKey("Upgrade")) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                var request = new ObjectMapper().readTree(exchange.getRequestBody());
                if (!"test-model".equals(request.path("model").asText())
                        || !"test".equals(request.path("messages").path(0).path("content").asText())) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                boolean stream = request.path("stream").asBoolean();
                String response = stream
                        ? """
                          data: {"id":"test","choices":[{"index":0,"delta":{"role":"assistant","content":"streaming"},"finish_reason":"stop"}]}

                          data: [DONE]

                          """
                        : """
                          {"id":"test","choices":[{"index":0,"message":{"role":"assistant","content":"ordinary"},"finish_reason":"stop"}]}
                          """;
                exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            contextRunner.withPropertyValues(
                            "spring.ai.llm.api-key=test-key",
                            "spring.ai.llm.base-url=http://127.0.0.1:" + server.getAddress().getPort() + basePath,
                            "spring.ai.llm.chat.options.model=test-model")
                    .run(context -> {
                        ChatClient client = context.getBean(ChatClient.class);
                        assertThat(client.prompt("test").call().content()).isEqualTo("ordinary");
                        assertThat(client.prompt("test").stream().content().collectList()
                                .block(Duration.ofSeconds(5))).containsExactly("streaming");
                    });
        } finally {
            server.stop(0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(JobLogAiConfiguration.class)
    static class TestConfiguration {}
}
