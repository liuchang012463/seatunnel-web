package org.apache.seatunnel.plugin.datasource.elasticsearch.param;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class ElasticsearchConnectionParamConverter implements ConnectionParamConverter {

    @Override
    public ElasticsearchConnectionParam createConnectionParams(String connectionJson) {
        String json = StringUtils.defaultIfBlank(connectionJson, "{}");
        ObjectNode object = JSONUtils.parseObject(json);
        JsonNode hostsNode = object.get("hosts");
        if (hostsNode != null && hostsNode.isArray()) {
            StringBuilder hosts = new StringBuilder();
            for (JsonNode host : hostsNode) {
                if (host != null && !host.isNull() && StringUtils.isNotBlank(host.asText())) {
                    if (hosts.length() > 0) {
                        hosts.append(',');
                    }
                    hosts.append(host.asText());
                }
            }
            object.put("hosts", hosts.toString());
        }

        ElasticsearchConnectionParam param = JSONUtils.parseObject(
                object.toString(), ElasticsearchConnectionParam.class);
        if (param == null) {
            throw new IllegalArgumentException("Elasticsearch connection param must not be null");
        }
        if (param.getAuthType() == null) {
            param.setAuthType(ElasticsearchAuthType.NONE);
        }
        if (param.getTlsVerifyCertificate() == null) {
            param.setTlsVerifyCertificate(true);
        }
        if (param.getTlsVerifyHostname() == null) {
            param.setTlsVerifyHostname(true);
        }
        if (param.getConnectTimeoutMs() == null) {
            param.setConnectTimeoutMs(10000);
        }
        if (param.getSocketTimeoutMs() == null) {
            param.setSocketTimeoutMs(60000);
        }
        param.setDbType(DbType.ELASTICSEARCH);
        return param;
    }

    @Override
    public void checkDatasourceParam(ConnectionParam connectionParam) {
        if (!(connectionParam instanceof ElasticsearchConnectionParam)) {
            throw new IllegalArgumentException("Invalid Elasticsearch connection param type");
        }

        ElasticsearchConnectionParam param = (ElasticsearchConnectionParam) connectionParam;
        if (param.hostList().isEmpty()) {
            throw new IllegalArgumentException("Elasticsearch hosts cannot be empty");
        }
        for (String host : param.hostList()) {
            validateHost(host);
        }
        requirePositive(param.getConnectTimeoutMs(), "connectTimeoutMs");
        requirePositive(param.getSocketTimeoutMs(), "socketTimeoutMs");
        validateAuthentication(param);
    }

    private void validateHost(String host) {
        URI uri;
        try {
            uri = URI.create(host);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Elasticsearch host is invalid", e);
        }
        String scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Elasticsearch hosts must use http or https");
        }
        if (StringUtils.isBlank(uri.getHost())) {
            throw new IllegalArgumentException("Elasticsearch host must include a host name");
        }
        if (StringUtils.isNotBlank(uri.getQuery()) || StringUtils.isNotBlank(uri.getFragment())) {
            throw new IllegalArgumentException("Elasticsearch host must not include query or fragment");
        }
    }

    private void validateAuthentication(ElasticsearchConnectionParam param) {
        ElasticsearchAuthType authType = param.getAuthType() == null
                ? ElasticsearchAuthType.NONE : param.getAuthType();
        switch (authType) {
            case NONE -> {
                if (StringUtils.isNotBlank(param.getUsername())
                        || StringUtils.isNotBlank(param.getPassword())
                        || StringUtils.isNotBlank(param.getApiKeyId())
                        || StringUtils.isNotBlank(param.getApiKey())
                        || StringUtils.isNotBlank(param.getApiKeyEncoded())) {
                    throw new IllegalArgumentException(
                            "Elasticsearch credentials require a non-NONE authType");
                }
            }
            case BASIC -> {
                requireText(param.getUsername(), "username");
                requireText(param.getPassword(), "password");
            }
            case API_KEY -> {
                requireText(param.getApiKeyId(), "apiKeyId");
                requireText(param.getApiKey(), "apiKey");
                if (StringUtils.isNotBlank(param.getApiKeyEncoded())) {
                    throw new IllegalArgumentException(
                            "API_KEY cannot configure apiKeyEncoded at the same time");
                }
            }
            case API_KEY_ENCODED -> {
                requireText(param.getApiKeyEncoded(), "apiKeyEncoded");
                if (StringUtils.isNotBlank(param.getApiKeyId())
                        || StringUtils.isNotBlank(param.getApiKey())) {
                    throw new IllegalArgumentException(
                            "API_KEY_ENCODED cannot configure apiKeyId or apiKey");
                }
            }
        }
    }

    private void requirePositive(Integer value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("Elasticsearch " + field + " must be greater than 0");
        }
    }

    private void requireText(String value, String field) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("Elasticsearch " + field + " cannot be empty");
        }
    }
}
