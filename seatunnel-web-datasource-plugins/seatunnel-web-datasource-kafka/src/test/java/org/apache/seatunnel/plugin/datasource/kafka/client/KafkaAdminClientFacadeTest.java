package org.apache.seatunnel.plugin.datasource.kafka.client;

import com.sun.net.httpserver.HttpServer;
import org.apache.seatunnel.plugin.datasource.kafka.param.KafkaConnectionParam;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaAdminClientFacadeTest {

    @Test
    void shouldVerifyConnectivityAndSortVisibleTopics() {
        KafkaAdminClientFacade facade = new KafkaAdminClientFacade(
                ignored -> operations(Set.of("z-orders", "__consumer_offsets", "a-orders"), null));

        assertTrue(facade.checkDataSourceConnectivity(param()));
        assertEquals(List.of("a-orders", "z-orders"), facade.listTopics(param()));
    }

    @Test
    void shouldReturnFalseForTimeoutAndAuthenticationFailure() {
        KafkaAdminClientFacade timeout = new KafkaAdminClientFacade(
                ignored -> operations(Set.of(), new TimeoutException("secret-password")));
        KafkaAdminClientFacade authentication = new KafkaAdminClientFacade(
                ignored -> operations(Set.of(), new SecurityException("secret-password")));

        assertFalse(timeout.checkDataSourceConnectivity(param()));
        assertFalse(authentication.checkDataSourceConnectivity(param()));
    }

    @Test
    void topicFailureShouldBeSanitized() {
        KafkaAdminClientFacade facade = new KafkaAdminClientFacade(
                ignored -> operations(Set.of(), new SecurityException("secret-password")));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> facade.listTopics(param()));
        assertFalse(error.getMessage().contains("secret-password"));
    }

    @Test
    void verifiesConfiguredRegistryAlongsideTheBroker() throws Exception {
        KafkaAdminClientFacade facade = new KafkaAdminClientFacade(
                ignored -> operations(Set.of(), null));
        KafkaConnectionParam param = param();
        HttpServer registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        registry.createContext("/registry/subjects", exchange -> {
            byte[] response = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        registry.start();
        try {
            String url = "http://127.0.0.1:" + registry.getAddress().getPort();
            param.setSchemaRegistryUrl(url + "/registry/");
            assertTrue(facade.checkDataSourceConnectivity(param));

            param.setSchemaRegistryUrl(url + "/wrong");
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> facade.checkDataSourceConnectivity(param));
            assertTrue(failure.getMessage().contains("HTTP 404"));
        } finally {
            registry.stop(0);
        }
    }

    private KafkaConnectionParam param() {
        KafkaConnectionParam param = new KafkaConnectionParam();
        param.setBootstrapServers("broker:9092");
        param.setRequestTimeoutMs(1000);
        return param;
    }

    private KafkaAdminClientFacade.AdminOperations operations(Set<String> topics, Exception failure) {
        return new KafkaAdminClientFacade.AdminOperations() {
            @Override
            public void verifyCluster(long timeoutMs) throws Exception {
                if (failure != null) {
                    throw failure;
                }
            }

            @Override
            public Set<String> listTopics(long timeoutMs) throws Exception {
                if (failure != null) {
                    throw failure;
                }
                return topics;
            }

            @Override
            public void close() {}
        };
    }
}
