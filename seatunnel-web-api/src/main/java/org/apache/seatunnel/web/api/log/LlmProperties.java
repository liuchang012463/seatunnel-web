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

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for the provider-agnostic LLM endpoint. */
@Getter
@Setter
@ConfigurationProperties(prefix = "spring.ai.llm")
public class LlmProperties {

    private String apiKey = "";
    private String baseUrl = "";
    private ChatProperties chat = new ChatProperties();
    private EmbeddingProperties embedding = new EmbeddingProperties();

    /** Returns the model configured at {@code spring.ai.llm.chat.options.model}. */
    public String getModel() {
        if (chat == null || chat.getOptions() == null || chat.getOptions().getModel() == null) {
            return "";
        }
        return chat.getOptions().getModel();
    }

    @Getter
    @Setter
    public static class ChatProperties {
        private OptionsProperties options = new OptionsProperties();
    }

    @Getter
    @Setter
    public static class OptionsProperties {
        private String model = "";
    }

    @Getter
    @Setter
    public static class EmbeddingProperties {
        private boolean enabled;
    }
}
