package org.apache.seatunnel.plugin.datasource.kafka.param;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.form.FieldType;
import org.apache.seatunnel.web.spi.form.FormField;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KafkaConnectionParam implements ConnectionParam {

    @FormField(label = "Bootstrap Servers", required = true, order = 1, placeholder = "localhost:9092")
    private String bootstrapServers;

    @FormField(label = "安全协议", required = true, type = FieldType.SELECT, order = 2, defaultValue = "PLAINTEXT")
    private KafkaSecurityProtocol securityProtocol = KafkaSecurityProtocol.PLAINTEXT;

    @FormField(label = "SASL 机制", type = FieldType.SELECT, order = 3)
    private KafkaSaslMechanism saslMechanism;

    @FormField(label = "用户名", order = 4)
    private String username;

    @FormField(label = "密码", type = FieldType.PASSWORD, order = 5)
    private String password;

    @FormField(label = "Client ID", order = 6, defaultValue = "seatunnel-web")
    private String clientId = "seatunnel-web";

    @FormField(label = "请求超时（毫秒）", type = FieldType.NUMBER, order = 7, defaultValue = "10000")
    private Integer requestTimeoutMs = 10000;

    @FormField(label = "Kafka 高级配置（JSON）", type = FieldType.TEXTAREA, order = 8)
    private Map<String, String> kafkaConfig = new LinkedHashMap<>();

    private DbType dbType = DbType.KAFKA;

    @Override
    public String getUser() {
        return username;
    }

    @Override
    public void setUser(String user) {
        this.username = user;
    }

    @Override
    public String toString() {
        return "KafkaConnectionParam{bootstrapServers='" + bootstrapServers
                + "', securityProtocol=" + securityProtocol
                + ", saslMechanism=" + saslMechanism
                + ", clientId='" + clientId
                + "', requestTimeoutMs=" + requestTimeoutMs
                + ", kafkaConfigKeys=" + kafkaConfig.keySet()
                + "}";
    }
}
