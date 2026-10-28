package org.apache.seatunnel.web.api.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.plugin.datasource.s3.param.MinioConnectionParam;
import org.apache.seatunnel.plugin.datasource.s3.param.MinioConnectionParamConverter;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.DataSourceSecretSupport;
import org.apache.seatunnel.web.api.service.MetadataBindingCommandService;
import org.apache.seatunnel.web.dao.entity.BusinessSystem;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.DataSourceUnit;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.dao.repository.JobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionDao;
import org.apache.seatunnel.web.spi.bean.dto.DataSourceDTO;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceVO;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataSourceSecretHandlingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Long DATA_SOURCE_ID = 9123L;

    @Mock private DataSourceDao dataSourceDao;
    @Mock private BusinessSystemDao businessSystemDao;
    @Mock private DataSourceUnitDao dataSourceUnitDao;
    @Mock private MetadataBindingCommandService metadataBindingCommandService;
    @Mock private JobDefinitionDao jobDefinitionDao;
    @Mock private StreamingJobDefinitionDao streamingJobDefinitionDao;
    @Mock private CurrentUserProvider currentUserProvider;

    @InjectMocks private DataSourceServiceImpl service;

    @Test
    void responseRedactionUsesPasswordMetadataAndPreservesCredentialIdentifiers() throws Exception {
        String original = "{\"type\":\"S3\",\"accessKey\":\"access-key-secret\","
                + "\"secretKey\":\"s3-secret\",\"apiKeyEncoded\":\"encoded-secret\","
                + "\"apiKeyId\":\"public-key-id\",\"other\":["
                + "{\"key\":\"tlsTruststorePassword\",\"value\":\"trust-secret\"},"
                + "{\"key\":\"region\",\"value\":\"us-east-1\"}]}";
        String normalized = "{\"user\":\"access-key-alias\",\"password\":\"password-alias\","
                + "\"secretKey\":\"s3-secret\",\"other\":["
                + "{\"key\":\"tlsTruststorePassword\",\"value\":\"trust-secret\"}]}";

        DataSource dataSource = new DataSource();
        dataSource.setDbType(DbType.S3);
        dataSource.setOriginalJson(original);
        dataSource.setConnectionParams(normalized);

        DataSourceSecretSupport.redact(dataSource);

        assertEquals(null, MAPPER.readTree(dataSource.getOriginalJson()).get("accessKey"));
        assertEquals("public-key-id", MAPPER.readTree(dataSource.getOriginalJson()).get("apiKeyId").asText());
        assertEquals("us-east-1", MAPPER.readTree(dataSource.getOriginalJson())
                .get("other").get(1).get("value").asText());
        assertFalse(MAPPER.readTree(dataSource.getOriginalJson()).get("other").get(0).has("value"));
        assertFalse(dataSource.getOriginalJson().contains("s3-secret"));
        assertFalse(dataSource.getOriginalJson().contains("encoded-secret"));
        assertFalse(dataSource.getConnectionParams().contains("access-key-alias"));
        assertFalse(dataSource.getConnectionParams().contains("password-alias"));
        assertFalse(dataSource.getConnectionParams().contains("trust-secret"));
        assertTrue(dataSource.getConfiguredSecretFields().contains("accessKey"));
        assertTrue(dataSource.getConfiguredSecretFields().contains("secretKey"));
        assertTrue(dataSource.getConfiguredSecretFields().contains("apiKeyEncoded"));
        assertTrue(dataSource.getConfiguredSecretFields().contains("other:tlsTruststorePassword"));
        assertTrue(dataSource.getSavedSecretFields().contains("accessKey"));
        assertTrue(dataSource.getSavedSecretFields().contains("other:tlsTruststorePassword"));

        DataSourceVO vo = new DataSourceVO();
        vo.setDbType(DbType.S3);
        vo.setOriginalJson(original);
        vo.setConnectionParams(normalized);
        DataSourceSecretSupport.redact(vo);
        assertFalse(vo.getOriginalJson().contains("s3-secret"));
        assertTrue(vo.getConfiguredSecretFields().contains("secretKey"));
    }

    @Test
    void mergePreservesBlankSecretsAndTreatsNonEmptyInputAsTheReplacement() throws Exception {
        String edited = "{\"type\":\"S3\",\"endpoint\":\"https://new-host\","
                + "\"accessKey\":\"new-access-id\",\"secretKey\":\"******\",\"other\":["
                + "{\"key\":\"tlsTruststorePassword\",\"value\":\"\"},"
                + "{\"key\":\"ssl\",\"value\":\"false\"}]}";
        String savedOriginal = "{\"type\":\"S3\",\"endpoint\":\"https://old-host\","
                + "\"accessKey\":\"old-access-id\",\"secretKey\":\"saved-secret\",\"other\":["
                + "{\"key\":\"tlsTruststorePassword\",\"value\":\"saved-trust-secret\"},"
                + "{\"key\":\"ssl\",\"value\":\"true\"}]}";

        String merged = DataSourceSecretSupport.mergeSecrets(DbType.S3, edited, savedOriginal,
                "{\"secretKey\":\"normalized-saved-secret\"}");
        JsonNode result = MAPPER.readTree(merged);

        assertEquals("https://new-host", result.get("endpoint").asText());
        assertEquals("new-access-id", result.get("accessKey").asText());
        assertEquals("******", result.get("secretKey").asText());
        assertEquals("saved-trust-secret", result.get("other").get(0).get("value").asText());
        assertEquals("false", result.get("other").get(1).get("value").asText());

        String blankSecret = edited.replace("\"secretKey\":\"******\"", "\"secretKey\":\"\"");
        String preserved = DataSourceSecretSupport.mergeSecrets(DbType.S3, blankSecret, savedOriginal,
                "{\"secretKey\":\"normalized-saved-secret\"}");
        assertEquals("saved-secret", MAPPER.readTree(preserved).get("secretKey").asText());

        String fallback = DataSourceSecretSupport.mergeSecrets(DbType.MYSQL,
                "{\"type\":\"MYSQL\",\"host\":\"new-host\",\"port\":\"3306\","
                        + "\"database\":\"orders\",\"user\":\"app\"}",
                "{\"type\":\"MYSQL\",\"host\":\"old-host\"}",
                "{\"password\":\"stored-only-in-connection-params\"}");
        assertEquals("stored-only-in-connection-params", MAPPER.readTree(fallback).get("password").asText());
        String whitespacePassword = DataSourceSecretSupport.mergeSecrets(DbType.MYSQL,
                "{\"type\":\"MYSQL\",\"password\":\"   \"}",
                "{\"type\":\"MYSQL\",\"password\":\"old\"}", null);
        assertEquals("   ", MAPPER.readTree(whitespacePassword).get("password").asText());
        assertTrue(DataSourceSecretSupport.savedSecretFields(DbType.MYSQL,
                "{\"password\":\"\"}").contains("password"));
        assertTrue(DataSourceSecretSupport.configuredSecretFields(DbType.MINIO,
                "{\"user\":\"minio-access\",\"password\":\"minio-secret\"}")
                .containsAll(java.util.List.of("accessKey", "secretKey")));
        assertTrue(DataSourceSecretSupport.savedSecretFields(DbType.MINIO,
                "{\"user\":\"\",\"password\":\"\"}")
                .containsAll(java.util.List.of("accessKey", "secretKey")));
    }

    @Test
    void minioConverterUsesNewCredentialsWithoutDerivedAliasesOverwritingThem() throws Exception {
        String edited = "{\"endpoint\":\"http://minio.example:9000\",\"region\":\"us-east-1\","
                + "\"bucket\":\"data\",\"basePath\":\"/\",\"accessKey\":\"new-access\","
                + "\"secretKey\":\"******\"}";
        String savedOriginal = "{\"accessKey\":\"old-access\",\"secretKey\":\"old-secret\"}";
        MinioConnectionParamConverter converter = new MinioConnectionParamConverter();
        MinioConnectionParam savedParam = converter.createConnectionParams(savedOriginal);
        String savedConnectionParams = MAPPER.writeValueAsString(savedParam);
        assertEquals("old-access", MAPPER.readTree(savedConnectionParams).get("user").asText());
        assertEquals("old-secret", MAPPER.readTree(savedConnectionParams).get("password").asText());

        String merged = DataSourceSecretSupport.mergeSecrets(
                DbType.MINIO, edited, savedOriginal, savedConnectionParams);
        MinioConnectionParam converted = converter.createConnectionParams(merged);

        assertEquals("new-access", converted.getAccessKey());
        assertEquals("******", converted.getSecretKey());
        assertFalse(MAPPER.readTree(merged).has("user"));
        assertFalse(MAPPER.readTree(merged).has("password"));
    }

    @Test
    void updatePersistsSavedPasswordAndEditedHostWhenPasswordIsBlank() throws Exception {
        DataSource existing = mysqlDataSource();
        when(dataSourceDao.queryById(DATA_SOURCE_ID)).thenReturn(existing);
        when(dataSourceDao.checkNameExcludeId("orders-edited", DATA_SOURCE_ID)).thenReturn(false);
        when(businessSystemDao.queryById(17L)).thenReturn(activeSystem());
        when(dataSourceUnitDao.queryById(23L)).thenReturn(activeUnit());
        when(jobDefinitionDao.existsByDatasourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(streamingJobDefinitionDao.existsByDatasourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId()).thenReturn(7);

        DataSourceDTO request = new DataSourceDTO();
        request.setName("orders-edited");
        request.setBusinessSystemId(17L);
        request.setDbType(DbType.MYSQL);
        request.setConnectionParams("{\"type\":\"MYSQL\",\"host\":\"edited-host\","
                + "\"port\":\"3306\",\"database\":\"orders\",\"user\":\"app\",\"password\":\"\"}");

        service.updateDataSource(DATA_SOURCE_ID, request);

        ArgumentCaptor<DataSource> saved = ArgumentCaptor.forClass(DataSource.class);
        verify(dataSourceDao).updateById(saved.capture());
        JsonNode connection = MAPPER.readTree(saved.getValue().getConnectionParams());
        JsonNode original = MAPPER.readTree(saved.getValue().getOriginalJson());
        assertEquals("edited-host", connection.get("host").asText());
        assertEquals("existing-password", connection.get("password").asText());
        assertEquals("existing-password", original.get("password").asText());
    }

    @Test
    void editConnectionTestMergesSecretByDatasourceIdButUsesEditedParameters() throws Exception {
        DataSource existing = mysqlDataSource();
        when(dataSourceDao.queryById(DATA_SOURCE_ID)).thenReturn(existing);
        DataSourceServiceImpl spy = spy(new DataSourceServiceImpl());
        ReflectionTestUtils.setField(spy, "dataSourceDao", dataSourceDao);
        doReturn(true).when(spy).connectionTestWithParam(org.mockito.ArgumentMatchers.anyString());

        boolean tested = spy.connectionTestWithParam(DATA_SOURCE_ID,
                "{\"type\":\"MYSQL\",\"host\":\"test-host\",\"port\":\"3306\","
                        + "\"database\":\"orders\",\"user\":\"app\"}");

        assertTrue(tested);
        ArgumentCaptor<String> connectionJson = ArgumentCaptor.forClass(String.class);
        verify(spy).connectionTestWithParam(connectionJson.capture());
        JsonNode merged = MAPPER.readTree(connectionJson.getValue());
        assertEquals("test-host", merged.get("host").asText());
        assertEquals("existing-password", merged.get("password").asText());
    }

    @Test
    void systemManagedProjectionStillRemovesItsEntireConnectionPayload() {
        DataSource dataSource = mysqlDataSource();
        dataSource.setSystemManaged(true);
        dataSource.setConfiguredSecretFields(java.util.List.of("password"));

        DataSourceSecretSupport.redact(dataSource);

        assertEquals(null, dataSource.getConnectionParams());
        assertEquals(null, dataSource.getOriginalJson());
        assertEquals(null, dataSource.getConfiguredSecretFields());
        assertEquals(null, dataSource.getSavedSecretFields());
    }

    private DataSource mysqlDataSource() {
        DataSource dataSource = new DataSource();
        dataSource.setId(DATA_SOURCE_ID);
        dataSource.setName("orders");
        dataSource.setDbType(DbType.MYSQL);
        dataSource.setBusinessSystemId(17L);
        dataSource.setOriginalJson("{\"type\":\"MYSQL\",\"host\":\"old-host\","
                + "\"port\":\"3306\",\"database\":\"orders\",\"user\":\"app\","
                + "\"password\":\"existing-password\"}");
        dataSource.setConnectionParams("{\"type\":\"MYSQL\",\"host\":\"old-host\","
                + "\"port\":\"3306\",\"database\":\"orders\",\"user\":\"app\","
                + "\"password\":\"existing-password\"}");
        return dataSource;
    }

    private BusinessSystem activeSystem() {
        BusinessSystem system = new BusinessSystem();
        system.setId(17L);
        system.setUnitId(23L);
        system.setStatus(1);
        return system;
    }

    private DataSourceUnit activeUnit() {
        DataSourceUnit unit = new DataSourceUnit();
        unit.setId(23L);
        unit.setStatus(1);
        return unit;
    }
}
