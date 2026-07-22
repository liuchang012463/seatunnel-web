package org.apache.seatunnel.plugin.datasource.kafka.param;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.LinkedHashMap;

public class KafkaConnectionParamConverter implements ConnectionParamConverter {

    @Override
    public KafkaConnectionParam createConnectionParams(String connectionJson) {
        String sanitized = sanitizeEmptyMapFields(connectionJson);
        KafkaConnectionParam param = JSONUtils.parseObject(sanitized, KafkaConnectionParam.class);
        if (param == null) {
            throw new IllegalArgumentException("Kafka connection param must not be null");
        }
        if (param.getKafkaConfig() == null) {
            param.setKafkaConfig(new LinkedHashMap<>());
        }
        param.setDbType(DbType.KAFKA);
        return param;
    }

    @Override
    public void checkDatasourceParam(ConnectionParam connectionParam) {
        if (!(connectionParam instanceof KafkaConnectionParam)) {
            throw new IllegalArgumentException("Invalid Kafka connection param type");
        }
        KafkaConnectionParam param = (KafkaConnectionParam) connectionParam;
        if (StringUtils.isBlank(param.getBootstrapServers())) {
            throw new IllegalArgumentException("Kafka bootstrapServers cannot be empty");
        }
        if (param.getRequestTimeoutMs() == null || param.getRequestTimeoutMs() <= 0) {
            throw new IllegalArgumentException("Kafka requestTimeoutMs must be greater than 0");
        }
        boolean sasl = param.getSecurityProtocol() == KafkaSecurityProtocol.SASL_PLAINTEXT
                || param.getSecurityProtocol() == KafkaSecurityProtocol.SASL_SSL;
        if (sasl && param.getSaslMechanism() != null
                && (StringUtils.isBlank(param.getUsername()) || StringUtils.isBlank(param.getPassword()))) {
            throw new IllegalArgumentException("Kafka SASL username and password cannot be empty");
        }
    }

    private static String sanitizeEmptyMapFields(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        return json.replaceAll(
                "\"kafkaConfig\"\\s*:\\s*\"\"",
                "\"kafkaConfig\":{}");
    }
}