package org.apache.seatunnel.web.api.utils;

import org.junit.jupiter.api.Test;

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
