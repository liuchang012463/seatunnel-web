package org.apache.seatunnel.web.api.utils;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoconSensitiveMaskUtilTest {

    @Test
    void masksNestedDatasourcePasswordsAndObjectStorageKeys() {
        String hocon = """
                source {
                  MySQL-CDC {
                    password = "mysql-test-password"
                    table-names = ["audit.source"]
                  }
                }
                sink {
                  S3File {
                    access_key = "minio-test-access-key"
                    secret_key = "minio-test-secret-key"
                    access_key_secret = "minio-test-key-secret"
                  }
                }
                """;

        String masked = HoconSensitiveMaskUtil.maskSensitiveInfo(hocon);

        assertFalse(masked.contains("mysql-test-password"));
        assertFalse(masked.contains("minio-test-access-key"));
        assertFalse(masked.contains("minio-test-secret-key"));
        assertFalse(masked.contains("minio-test-key-secret"));
        assertTrue(masked.contains("table-names"));
        assertTrue(masked.contains("******"));
    }

    @Test
    void masksDuckDbMinioCredentialsInJdbcProperties() {
        String hocon = """
                source {
                  Jdbc {
                    properties {
                      s3_endpoint = "minio.test:9000"
                      s3_access_key_id = "duckdb-test-access-key"
                      s3_secret_access_key = "duckdb-test-secret-key"
                    }
                  }
                }
                """;

        String masked = HoconSensitiveMaskUtil.maskSensitiveInfo(hocon);

        assertFalse(masked.contains("duckdb-test-access-key"));
        assertFalse(masked.contains("duckdb-test-secret-key"));
        assertTrue(masked.contains("minio.test:9000"));
    }

    @Test
    void masksElasticsearchApiKeysAndTlsPasswords() {
        String hocon = """
                source {
                  Elasticsearch {
                    hosts = ["https://es.test:9200"]
                    auth {
                      api_key_id = "es-test-key-id"
                      api_key = "es-test-api-key"
                      api_key_encoded = "es-test-encoded-key"
                    }
                    tls_keystore_password = "es-test-keystore-password"
                    tls_truststore_password = "es-test-truststore-password"
                  }
                }
                """;

        String masked = HoconSensitiveMaskUtil.maskSensitiveInfo(hocon);

        assertFalse(masked.contains("es-test-key-id"));
        assertFalse(masked.contains("es-test-api-key"));
        assertFalse(masked.contains("es-test-encoded-key"));
        assertFalse(masked.contains("es-test-keystore-password"));
        assertFalse(masked.contains("es-test-truststore-password"));
        assertTrue(masked.contains("https://es.test:9200"));
        assertTrue(masked.contains("******"));
    }

    @Test
    void masksAllHttpHeadersIncludingCustomAuthenticationHeaders() {
        String hocon = """
                source {
                  HTTP {
                    url = "https://api.test/v1/records"
                    headers {
                      Authorization = "Bearer http-test-bearer-token"
                      "X-Api-Key" = "http-test-api-key"
                      "X-Custom-Session" = "http-test-custom-header-secret"
                    }
                  }
                }
                """;

        String masked = HoconSensitiveMaskUtil.maskSensitiveInfo(hocon);

        assertFalse(masked.contains("http-test-bearer-token"));
        assertFalse(masked.contains("http-test-api-key"));
        assertFalse(masked.contains("http-test-custom-header-secret"));
        assertTrue(masked.contains("https://api.test/v1/records"));
        assertTrue(masked.contains("headers"));
        assertTrue(masked.contains("******"));
    }

    @Test
    void masksKafkaSaslJaasConfiguration() {
        String hocon = """
                source {
                  Kafka {
                    bootstrap.servers = "kafka.test:9092"
                    "kafka.config" = {
                      "sasl.jaas.config" = "KafkaClient required username=\\\"kafka-test-user\\\" password=\\\"kafka-test-sasl-password\\\";"
                    }
                  }
                }
                """;

        String masked = HoconSensitiveMaskUtil.maskSensitiveInfo(hocon);

        assertFalse(masked.contains("kafka-test-sasl-password"));
        assertFalse(masked.contains("kafka-test-user"));
        assertTrue(masked.contains("kafka.test:9092"));
        assertTrue(masked.contains("******"));
    }

    @Test
    void masksCredentialsEmbeddedInJdbcHttpAndOracleUrls() {
        String hocon = """
                source {
                  JDBC {
                    url = "jdbc:mysql://jdbc-url-user:jdbc-url-password@db.test:3306/app?user=jdbc-query-user&password=jdbc-query-password&sslMode=REQUIRED"
                  }
                  HTTP {
                    url = "https://http-url-user:http-url-password@api.test/records?api_key=http-query-api-key&key=query-api-secret&auth=query-auth-secret&limit=10"
                  }
                  Oracle {
                    url = "jdbc:oracle:thin:oracle-url-user/oracle-P@ss:word;tail@db.test:1521:orcl"
                  }
                  SQLServer {
                    url = "jdbc:sqlserver://db.test:1433;databaseName=app;password={sql;server;secret};encrypt=true"
                  }
                }
                """;

        String masked = HoconSensitiveMaskUtil.maskSensitiveInfo(hocon);

        assertFalse(masked.contains("jdbc-url-user"));
        assertFalse(masked.contains("jdbc-url-password"));
        assertFalse(masked.contains("jdbc-query-user"));
        assertFalse(masked.contains("jdbc-query-password"));
        assertFalse(masked.contains("http-url-user"));
        assertFalse(masked.contains("http-url-password"));
        assertFalse(masked.contains("http-query-api-key"));
        assertFalse(masked.contains("query-api-secret"));
        assertFalse(masked.contains("query-auth-secret"));
        assertFalse(masked.contains("oracle-url-user"));
        assertFalse(masked.contains("oracle-P@ss:word;tail"));
        assertFalse(masked.contains("ss:word;tail"));
        assertFalse(masked.contains("sql;server;secret"));
        assertTrue(masked.contains("db.test:3306/app"));
        assertTrue(masked.contains("sslMode=REQUIRED"));
        assertTrue(masked.contains("api.test/records"));
        assertTrue(masked.contains("limit=10"));
        assertTrue(masked.contains("db.test:1521:orcl"));
        assertTrue(masked.contains("encrypt=true"));
    }

    @Test
    void masksCredentialsInElasticsearchHostsList() {
        String hocon = """
                source {
                  Elasticsearch {
                    hosts = ["https://es-url-user:es-url-password@es.test:9200"]
                  }
                }
                """;

        String masked = HoconSensitiveMaskUtil.maskSensitiveInfo(hocon);

        assertFalse(masked.contains("es-url-user"));
        assertFalse(masked.contains("es-url-password"));
        assertTrue(masked.contains("https://******@es.test:9200"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void masksCredentialParametersAndErrorMessagesInEnginePayloadMaps() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("key", "http-param-api-key");
        params.put("limit", "10");

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("key", "http-query-api-key");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("params", params);
        payload.put("query", query);
        payload.put("errorMsg",
                "connect failed password=\"engine-error-password\" apiKey=engine-error-api-key");

        Map<String, Object> masked =
                (Map<String, Object>) HoconSensitiveMaskUtil.maskSensitiveValues(payload);

        assertEquals("******", ((Map<String, Object>) masked.get("params")).get("key"));
        assertEquals("10", ((Map<String, Object>) masked.get("params")).get("limit"));
        assertEquals("******", ((Map<String, Object>) masked.get("query")).get("key"));
        String error = String.valueOf(masked.get("errorMsg"));
        assertFalse(error.contains("engine-error-password"));
        assertFalse(error.contains("engine-error-api-key"));
        assertTrue(error.contains("******"));
    }

    @Test
    void masksBareKeyValueCredentialsInFreeFormText() {
        String masked = HoconSensitiveMaskUtil.maskSensitiveText(
                "failed: password = \"plain-password\"; token=plain-token");

        assertFalse(masked.contains("plain-password"));
        assertFalse(masked.contains("plain-token"));
        assertTrue(masked.contains("******"));
    }

    /**
     * Diagnostic fields that merely contain a credential word root (tokens counted by a model,
     * a vocabulary size, a search keyword, a user id, a verify switch) are not credentials and
     * must survive masking readable.
     */
    @Test
    void doesNotMaskBusinessFieldsThatContainACredentialRoot() {
        String text = "max_tokens=512 total_tokens: 1024 tokenizer.vocab_size=30522 "
                + "?keyword=search&monkey=1&user_id=7 signature.verify=true secretariat.office=room5";

        assertEquals(text, HoconSensitiveMaskUtil.maskSensitiveText(text));
    }

    @Test
    void stillMasksCompoundAndBareCredentialRoots() {
        String masked = HoconSensitiveMaskUtil.maskSensitiveText(
                "api_key=k1 access-token=k2 client_secret=k3 token: k4 "
                        + "url?a=b&token=url-token&X-Amz-Signature=sig");

        assertFalse(masked.contains("k1"));
        assertFalse(masked.contains("k2"));
        assertFalse(masked.contains("k3"));
        assertFalse(masked.contains("k4"));
        assertFalse(masked.contains("url-token"));
        assertFalse(masked.contains("=sig"));
        assertTrue(masked.contains("a=b"));
    }

    /** The quotes of a masked JSON value are kept, so an embedded JSON fragment stays parseable. */
    @Test
    void keepsTheQuotesOfAMaskedJsonValue() {
        String masked = HoconSensitiveMaskUtil.maskSensitiveText(
                "connect failed: {\"password\":\"json-password\",\"ok\":true}");

        assertEquals("connect failed: {\"password\":\"******\",\"ok\":true}", masked);
    }

    /**
     * The Engine answers with JSON, so a quoted key must be masked as well.
     */
    @Test
    void masksQuotedCredentialsInFreeFormText() {
        String masked = HoconSensitiveMaskUtil.maskSensitiveText(
                "{\"password\":\"json-password\",\"url\":\"jdbc:mysql://root:db-pw@127.0.0.1:3306/db\"}");

        assertFalse(masked.contains("json-password"));
        assertFalse(masked.contains("db-pw"));
        assertTrue(masked.contains("******"));
    }

    @Test
    void masksTheWholeValueOfAnAuthorizationHeader() {
        String masked = HoconSensitiveMaskUtil.maskSensitiveText(
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature");

        assertFalse(masked.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertFalse(masked.contains("payload.signature"));
        assertTrue(masked.contains("******"));
    }

    @Test
    void fullyMasksConfigurationWhenHoconCannotBeParsed() {
        String malformedHocon = """
                source {
                  HTTP {
                    headers {
                      Authorization = "Bearer malformed-test-secret"
                """;

        String masked = HoconSensitiveMaskUtil.maskSensitiveInfo(malformedHocon);

        assertFalse(masked.contains("malformed-test-secret"));
        assertFalse(masked.contains("Authorization"));
        assertTrue(masked.contains("fully masked"));
    }
}
