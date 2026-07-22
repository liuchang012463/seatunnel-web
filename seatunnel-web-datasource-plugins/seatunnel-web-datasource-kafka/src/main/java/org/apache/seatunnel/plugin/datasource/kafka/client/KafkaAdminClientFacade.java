package org.apache.seatunnel.plugin.datasource.kafka.client;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectivityVerifier;
import org.apache.seatunnel.plugin.datasource.kafka.param.KafkaClientProperties;
import org.apache.seatunnel.plugin.datasource.kafka.param.KafkaConnectionParam;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;

import java.util.ArrayList;
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
            return true;
        } catch (Exception ignored) {
            return false;
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
