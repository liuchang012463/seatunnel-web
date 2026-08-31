package org.apache.seatunnel.plugin.datasource.http.param;

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
public class HttpConnectionParam implements ConnectionParam {

    @FormField(
            label = "Base URL",
            required = true,
            order = 1,
            placeholder = "https://api.example.com",
            description = "填写 API 服务的根地址，不要填写具体接口路径；例如 https://api.example.com。具体接口路径请在引接任务中填写。"
    )
    private String baseUrl;

    @FormField(
            label = "健康检查路径",
            order = 2,
            placeholder = "/health",
            description = "用于连接测试的相对路径，例如 /health；留空时使用 Base URL 本身进行检查。"
    )
    private String healthCheckPath;

    @FormField(label = "OpenAPI 文档地址", order = 12,
            placeholder = "https://api.example.com/openapi.json",
            description = "可选。填写 OpenAPI 3 或 Swagger 2 JSON 文档地址后，可在目录中浏览接口。")
    private String openApiSpecUrl;

    @FormField(label = "认证方式", required = true, type = FieldType.SELECT, order = 3, defaultValue = "NONE")
    private HttpAuthenticationType authenticationType = HttpAuthenticationType.NONE;

    @FormField(label = "用户名", order = 4, visibleWhen = "authenticationType=BASIC")
    private String username;

    @FormField(label = "密码", type = FieldType.PASSWORD, order = 5,
            visibleWhen = "authenticationType=BASIC")
    private String password;

    @FormField(label = "Bearer Token", type = FieldType.PASSWORD, order = 6,
            visibleWhen = "authenticationType=BEARER")
    private String bearerToken;

    @FormField(label = "API Key Header", order = 7, placeholder = "X-API-Key",
            visibleWhen = "authenticationType=API_KEY")
    private String apiKeyHeader;

    @FormField(label = "API Key Value", type = FieldType.PASSWORD, order = 8,
            visibleWhen = "authenticationType=API_KEY")
    private String apiKeyValue;

    @FormField(label = "默认 Headers（JSON）", type = FieldType.TEXTAREA, order = 9)
    private Map<String, String> defaultHeaders = new LinkedHashMap<>();

    @FormField(label = "连接超时（毫秒）", type = FieldType.NUMBER, order = 10, defaultValue = "12000")
    private Integer connectTimeoutMs = 12000;

    @FormField(label = "读取超时（毫秒）", type = FieldType.NUMBER, order = 11, defaultValue = "60000")
    private Integer socketTimeoutMs = 60000;

    private DbType dbType = DbType.HTTP;

    @Override
    public String getUrl() {
        return baseUrl;
    }

    @Override
    public void setUrl(String url) {
        this.baseUrl = url;
    }

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
        return "HttpConnectionParam{baseUrl='" + baseUrl
                + "', healthCheckPath='" + healthCheckPath
                + "', authenticationType=" + authenticationType
                + ", defaultHeaderNames=" + defaultHeaders.keySet()
                + ", connectTimeoutMs=" + connectTimeoutMs
                + ", socketTimeoutMs=" + socketTimeoutMs
                + "}";
    }
}
