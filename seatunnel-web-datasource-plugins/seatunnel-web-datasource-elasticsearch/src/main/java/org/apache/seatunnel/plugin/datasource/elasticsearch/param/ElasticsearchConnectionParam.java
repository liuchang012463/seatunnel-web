package org.apache.seatunnel.plugin.datasource.elasticsearch.param;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.form.FieldType;
import org.apache.seatunnel.web.spi.form.FormField;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ElasticsearchConnectionParam implements ConnectionParam {

    @FormField(label = "ES 地址（逗号分隔）", required = true, order = 1,
            placeholder = "http://localhost:9200")
    private String hosts;

    @FormField(label = "认证方式", type = FieldType.SELECT, order = 2, defaultValue = "NONE")
    private ElasticsearchAuthType authType = ElasticsearchAuthType.NONE;

    @FormField(label = "用户名", order = 3)
    private String username;

    @FormField(label = "密码", type = FieldType.PASSWORD, order = 4)
    private String password;

    @FormField(label = "API Key ID", order = 5)
    private String apiKeyId;

    @FormField(label = "API Key", type = FieldType.PASSWORD, order = 6)
    private String apiKey;

    @FormField(label = "编码后的 API Key", type = FieldType.PASSWORD, order = 7)
    private String apiKeyEncoded;

    @FormField(label = "校验证书", type = FieldType.SWITCH, order = 8, defaultValue = "true")
    private Boolean tlsVerifyCertificate = true;

    @FormField(label = "校验主机名", type = FieldType.SWITCH, order = 9, defaultValue = "true")
    private Boolean tlsVerifyHostname = true;

    @FormField(label = "连接超时（毫秒）", type = FieldType.NUMBER, order = 10, defaultValue = "10000")
    private Integer connectTimeoutMs = 10000;

    @FormField(label = "请求超时（毫秒）", type = FieldType.NUMBER, order = 11, defaultValue = "60000")
    private Integer socketTimeoutMs = 60000;

    @FormField(label = "KeyStore 路径", order = 12)
    private String tlsKeystorePath;

    @FormField(label = "KeyStore 密码", type = FieldType.PASSWORD, order = 13)
    private String tlsKeystorePassword;

    @FormField(label = "TrustStore 路径", order = 14)
    private String tlsTruststorePath;

    @FormField(label = "TrustStore 密码", type = FieldType.PASSWORD, order = 15)
    private String tlsTruststorePassword;

    private DbType dbType = DbType.ELASTICSEARCH;

    @Override
    public String getUser() {
        return username;
    }

    @Override
    public void setUser(String user) {
        this.username = user;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String getUrl() {
        return hostList().stream().findFirst().orElse("");
    }

    @Override
    public void setUrl(String url) {
        this.hosts = url;
    }

    public List<String> hostList() {
        if (StringUtils.isBlank(hosts)) {
            return Collections.emptyList();
        }

        String value = hosts.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            try {
                return JSONUtils.toList(value, String.class).stream()
                        .map(String::trim)
                        .filter(StringUtils::isNotBlank)
                        .map(ElasticsearchConnectionParam::normalizeHost)
                        .collect(Collectors.toList());
            } catch (RuntimeException ignored) {
                // Fall through to the comma-separated form for a useful validation error.
            }
        }

        return Arrays.stream(value.split("[,;\\r\\n]"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .map(ElasticsearchConnectionParam::normalizeHost)
                .collect(Collectors.toList());
    }

    private static String normalizeHost(String value) {
        if (value.contains("://")) {
            return value;
        }
        return "http://" + value;
    }

    public URI firstHostUri() {
        return URI.create(hostList().stream().findFirst().orElse(""));
    }

    @Override
    public String toString() {
        return "ElasticsearchConnectionParam{hosts='" + hosts
                + "', authType=" + authType
                + ", tlsVerifyCertificate=" + tlsVerifyCertificate
                + ", tlsVerifyHostname=" + tlsVerifyHostname
                + ", connectTimeoutMs=" + connectTimeoutMs
                + ", socketTimeoutMs=" + socketTimeoutMs
                + "}";
    }
}
