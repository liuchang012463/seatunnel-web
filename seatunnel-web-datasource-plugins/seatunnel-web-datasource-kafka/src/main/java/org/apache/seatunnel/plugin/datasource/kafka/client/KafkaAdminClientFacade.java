package org.apache.seatunnel.plugin.datasource.kafka.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectivityVerifier;
import org.apache.seatunnel.plugin.datasource.kafka.param.KafkaClientProperties;
import org.apache.seatunnel.plugin.datasource.kafka.param.KafkaConnectionParam;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class KafkaAdminClientFacade implements ConnectivityVerifier {

    private final AdminOperationsFactory operationsFactory;

    public KafkaAdminClientFacade() {
        this(properties -> new KafkaAdminOperations(AdminClient.create(properties)));
    }

    KafkaAdminClientFacade(AdminOperationsFactory operationsFactory) {
        this.operationsFactory = operationsFactory;
    }

    @Override
    public boolean checkDataSourceConnectivity(ConnectionParam connectionParam) {
        KafkaConnectionParam param = requireKafkaParam(connectionParam);
        try (AdminOperations admin = operationsFactory.create(KafkaClientProperties.build(param))) {
            admin.verifyCluster(param.getRequestTimeoutMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
        if (StringUtils.isNotBlank(param.getSchemaRegistryUrl())) {
            verifySchemaRegistry(param);
        }
        return true;
    }

    private void verifySchemaRegistry(KafkaConnectionParam param) {
        // OpenMetadata 配置了 Schema Registry 时会读取 subjects，空列表也是有效响应。
        Duration timeout = Duration.ofMillis(param.getRequestTimeoutMs());
        HttpRequest request;
        try {
            URI base = URI.create(param.getSchemaRegistryUrl().trim());
            if (base.getHost() == null || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            URI subjects = new URI(base.getScheme(), null, base.getHost(), base.getPort(),
                    base.getPath().replaceFirst("/+$", "") + "/subjects", null, null);
            HttpRequest.Builder builder = HttpRequest.newBuilder(subjects).timeout(timeout)
                    .header("Accept", "application/vnd.schemaregistry.v1+json").GET();
            if (base.getUserInfo() != null) {
                String credentials = Base64.getEncoder()
                        .encodeToString(base.getUserInfo().getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + credentials);
            }
            request = builder.build();
        } catch (IllegalArgumentException | URISyntaxException e) {
            throw new IllegalArgumentException("Schema Registry 地址无效，请填写 HTTP 或 HTTPS 服务地址");
        }
        HttpResponse<String> response;
        try {
            response = HttpClient.newBuilder().connectTimeout(timeout).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Schema Registry 连接检查中断");
        } catch (IOException e) {
            throw new IllegalStateException("无法连接 Schema Registry：" + e.getClass().getSimpleName());
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Schema Registry 无法读取主题列表，HTTP " + response.statusCode());
        }
        ArrayNode subjects;
        try {
            subjects = JSONUtils.parseArray(response.body());
        } catch (RuntimeException e) {
            // 不将响应内容写入日志或返回给用户。
            throw new IllegalStateException("Schema Registry 返回的主题列表格式无效");
        }
        if (subjects == null) {
            throw new IllegalStateException("Schema Registry 返回的主题列表格式无效");
        }
        for (JsonNode subject : subjects) {
            if (!subject.isTextual()) {
                throw new IllegalStateException("Schema Registry 返回的主题列表格式无效");
            }
        }
    }

    public List<String> listTopics(KafkaConnectionParam param) {
        try (AdminOperations admin = operationsFactory.create(KafkaClientProperties.build(param))) {
            List<String> topics = new ArrayList<>(admin.listTopics(param.getRequestTimeoutMs()));
            topics.removeIf(topic -> topic.startsWith("__"));
            Collections.sort(topics);
            return topics;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list Kafka topics: connection or authentication failed");
        }
    }

    private KafkaConnectionParam requireKafkaParam(ConnectionParam connectionParam) {
        if (!(connectionParam instanceof KafkaConnectionParam)) {
            throw new IllegalArgumentException("Invalid Kafka connection param type");
        }
        return (KafkaConnectionParam) connectionParam;
    }

    @FunctionalInterface
    interface AdminOperationsFactory {
        AdminOperations create(Map<String, Object> properties);
    }

    interface AdminOperations extends AutoCloseable {
        void verifyCluster(long timeoutMs) throws Exception;

        Set<String> listTopics(long timeoutMs) throws Exception;

        @Override
        void close();
    }

    private static final class KafkaAdminOperations implements AdminOperations {
        private final AdminClient adminClient;

        private KafkaAdminOperations(AdminClient adminClient) {
            this.adminClient = adminClient;
        }

        @Override
        public void verifyCluster(long timeoutMs) throws Exception {
            adminClient.describeCluster().nodes().get(timeoutMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public Set<String> listTopics(long timeoutMs) throws Exception {
            return adminClient.listTopics(new ListTopicsOptions().listInternal(false))
                    .names()
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            adminClient.close();
        }
    }
}
