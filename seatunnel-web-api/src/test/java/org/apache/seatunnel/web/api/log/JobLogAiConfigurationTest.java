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

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

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

    @Configuration(proxyBeanMethods = false)
    @Import(JobLogAiConfiguration.class)
    static class TestConfiguration {}
}
