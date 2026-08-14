package org.apache.seatunnel.plugin.datasource.http.param;

import org.apache.seatunnel.plugin.datasource.api.form.ReflectionFormGenerator;
import org.apache.seatunnel.web.spi.form.FormFieldConfig;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpConnectionParamConverterTest {

    private final HttpConnectionParamConverter converter = new HttpConnectionParamConverter();

    @Test
    void shouldParseMinimalConfigurationAndApplyDefaults() {
        HttpConnectionParam param =
                converter.createConnectionParams("{\"baseUrl\":\"https://api.example.com\"}");

        converter.checkDatasourceParam(param);

        assertEquals(DbType.HTTP, param.getDbType());
        assertEquals(HttpAuthenticationType.NONE, param.getAuthenticationType());
        assertEquals(12000, param.getConnectTimeoutMs());
        assertEquals(60000, param.getSocketTimeoutMs());
    }

    @Test
    void shouldValidateConditionalAuthenticationFields() {
        HttpConnectionParam basic = converter.createConnectionParams(
                "{\"baseUrl\":\"https://api.example.com\","
                        + "\"authenticationType\":\"BASIC\",\"username\":\"alice\"}");
        assertThrows(IllegalArgumentException.class, () -> converter.checkDatasourceParam(basic));

        HttpConnectionParam bearer = converter.createConnectionParams(
                "{\"baseUrl\":\"https://api.example.com\","
                        + "\"authenticationType\":\"BEARER\"}");
        assertThrows(IllegalArgumentException.class, () -> converter.checkDatasourceParam(bearer));

        HttpConnectionParam apiKey = converter.createConnectionParams(
                "{\"baseUrl\":\"https://api.example.com\","
                        + "\"authenticationType\":\"API_KEY\",\"apiKeyHeader\":\"X-API-Key\"}");
        assertThrows(IllegalArgumentException.class, () -> converter.checkDatasourceParam(apiKey));
    }

    @Test
    void shouldRejectUnsupportedUrlAndRawAuthenticationHeader() {
        HttpConnectionParam ftp =
                converter.createConnectionParams("{\"baseUrl\":\"ftp://example.com\"}");
        assertThrows(IllegalArgumentException.class, () -> converter.checkDatasourceParam(ftp));

        HttpConnectionParam header = converter.createConnectionParams(
                "{\"baseUrl\":\"https://api.example.com\","
                        + "\"defaultHeaders\":{\"Authorization\":\"secret\"}}");
        assertThrows(IllegalArgumentException.class, () -> converter.checkDatasourceParam(header));
    }

    @Test
    void shouldNotExposeSecretsInToString() {
        HttpConnectionParam param = converter.createConnectionParams(
                "{\"baseUrl\":\"https://api.example.com\","
                        + "\"authenticationType\":\"BASIC\","
                        + "\"username\":\"alice\",\"password\":\"very-secret\"}");

        assertFalse(param.toString().contains("very-secret"));
        assertFalse(param.toString().contains("alice"));
    }

    @Test
    void shouldExposeAuthenticationVisibilityRulesToFrontend() {
        List<FormFieldConfig> fields = ReflectionFormGenerator.generate(HttpConnectionParam.class);

        assertEquals("authenticationType=BASIC", field(fields, "username").getVisibleWhen());
        assertEquals("authenticationType=BASIC", field(fields, "password").getVisibleWhen());
        assertEquals("authenticationType=BEARER", field(fields, "bearerToken").getVisibleWhen());
        assertEquals("authenticationType=API_KEY", field(fields, "apiKeyHeader").getVisibleWhen());
        assertEquals("authenticationType=API_KEY", field(fields, "apiKeyValue").getVisibleWhen());
    }

    @Test
    void shouldExposeBaseUrlGuidanceToFrontend() {
        List<FormFieldConfig> fields = ReflectionFormGenerator.generate(HttpConnectionParam.class);

        assertEquals(
                "填写 API 服务的根地址，不要填写具体接口路径；例如 https://api.example.com。具体接口路径请在引接任务中填写。",
                field(fields, "baseUrl").getDescription());
        assertEquals(
                "用于连接测试的相对路径，例如 /health；留空时使用 Base URL 本身进行检查。",
                field(fields, "healthCheckPath").getDescription());
    }

    private FormFieldConfig field(List<FormFieldConfig> fields, String key) {
        return fields.stream()
                .filter(item -> key.equals(item.getKey()))
                .findFirst()
                .orElseThrow();
    }
}
