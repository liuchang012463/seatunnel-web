package org.apache.seatunnel.web.api.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.datasource.DataSourceCatalog;
import org.apache.seatunnel.plugin.datasource.api.datasource.FileDataSourceCatalog;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcCatalog;
import org.apache.seatunnel.plugin.datasource.api.modal.DataSourceTableColumn;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.plugin.datasource.http.client.HttpRequestSupport;
import org.apache.seatunnel.plugin.datasource.http.param.HttpConnectionParam;
import org.apache.seatunnel.web.api.service.DataSourceCatalogService;
import org.apache.seatunnel.web.api.service.DataSourceService;
import org.apache.seatunnel.web.common.QueryResult;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.core.time.TimeVariableJdbcSqlRenderService;
import org.apache.seatunnel.web.core.time.RuntimeParameterRenderer;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;
import org.apache.seatunnel.web.spi.bean.vo.ColumnOptionVO;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.apache.seatunnel.web.spi.bean.vo.FileEntryVO;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DataSourceCatalogServiceImpl implements DataSourceCatalogService {

    private static final String KEY_TABLE_PATH = "table_path";
    private static final String KEY_QUERY = "query";
    private static final String KEY_READ_MODE = "read_mode";

    private static final String KEY_PLUGIN_NAME = "pluginName";
    private static final String KEY_CONNECTOR_TYPE = "connectorType";

    private static final String KEY_PARAMS_LIST = "paramsList";
    private static final String KEY_PARAM_NAME = "paramName";
    private static final String KEY_PARAM_VALUE = "paramValue";

    private static final String READ_MODE_SQL = "sql";

    @Resource
    private DataSourceService dataSourceService;

    @Resource
    private TimeVariableJdbcSqlRenderService timeVariableJdbcSqlRenderService;

    @Override
    public List<OptionVO> listTable(Long datasourceId) {
        DataSource dataSource = getDataSourceOrThrow(datasourceId);
        ConnectionParam connectionParam = buildConnectionParam(dataSource);

        try {
            return getCatalog(dataSource, connectionParam).listOptions();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to list tables, datasourceId={}", datasourceId, e);
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR, e.getMessage());
        }
    }

    @Override
    public List<FileEntryVO> listFiles(Long datasourceId, String path) {
        validateDatasourceId(datasourceId);
        DataSource dataSource = getDataSourceOrThrow(datasourceId);
        DataSourceCatalog catalog = getCatalog(dataSource, buildConnectionParam(dataSource));
        if (!(catalog instanceof FileDataSourceCatalog)) {
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR,
                    dataSource.getDbType() + " does not support file catalog operations");
        }
        try {
            return ((FileDataSourceCatalog) catalog).listEntries(path);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to list remote files, datasourceId={}, path={}", datasourceId, path, e);
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR, e.getMessage());
        }
    }

    @Override
    public List<OptionVO> listTableReference(Long datasourceId, String matchMode, String keyword) {
        List<OptionVO> allTables = listTable(datasourceId);

        if (StringUtils.isBlank(keyword)) {
            return allTables;
        }

        if ("2".equals(matchMode)) {
            return allTables.stream()
                    .filter(table -> String.valueOf(table.getValue()).matches(keyword))
                    .collect(Collectors.toList());
        }

        if ("3".equals(matchMode)) {
            String[] exactNames = keyword.split(",");
            return allTables.stream()
                    .filter(table -> matchExactTable(String.valueOf(table.getValue()), exactNames))
                    .collect(Collectors.toList());
        }

        return allTables;
    }

    @Override
    public List<ColumnOptionVO> listColumn(Long datasourceId, Map<String, Object> requestBody) {
        validateDatasourceId(datasourceId);
        validateRequestBody(requestBody, "requestBody");

        DataSource dataSource = getDataSourceOrThrow(datasourceId);
        ConnectionParam connectionParam = buildConnectionParam(dataSource);

        try {
            Map<String, Object> columnRequestBody =
                    renderSqlQueryIfNecessary(dataSource, requestBody);

            List<DataSourceTableColumn> columns =
                    getJdbcCatalog(dataSource, connectionParam).listColumns(columnRequestBody);

            return columns.stream()
                    .map(column -> {
                        ColumnOptionVO optionVO = new ColumnOptionVO();
                        optionVO.setKey(column.getOrdinalPosition());
                        optionVO.setFieldName(column.getColumnName());
                        optionVO.setFieldType(column.getSourceType());
                        optionVO.setIsNullable(column.getIsNullable());
                        optionVO.setFieldComment(column.getColumnComment());
                        optionVO.setFieldKey(column.getColumnKey());
                        return optionVO;
                    })
                    .collect(Collectors.toList());
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to list columns, datasourceId={}, requestBody={}", datasourceId, requestBody, e);
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR, e.getMessage());
        }
    }

    @Override
    public QueryResult getTop20Data(Long datasourceId, Map<String, Object> requestBody) {
        validateDatasourceId(datasourceId);
        validateRequestBody(requestBody, "requestBody");

        DataSource dataSource = getDataSourceOrThrow(datasourceId);
        ConnectionParam connectionParam = buildConnectionParam(dataSource);

        try {
            Map<String, Object> previewRequestBody =
                    renderSqlQueryIfNecessary(dataSource, requestBody);

            JdbcCatalog jdbcCatalog = getJdbcCatalog(dataSource, connectionParam);

            QueryResult queryResult = jdbcCatalog.getTop20Data(previewRequestBody);
            Integer total = jdbcCatalog.count(previewRequestBody);

            if (queryResult == null) {
                queryResult = new QueryResult();
            }

            queryResult.setTotal(total);
            return queryResult;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to query top20 preview data, datasourceId={}, requestBody={}", datasourceId, requestBody, e);
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR, e.getMessage());
        }
    }

    @Override
    public Map<String, Object> parseHttpResponse(
            Long datasourceId, Map<String, Object> requestBody) {
        validateDatasourceId(datasourceId);
        validateRequestBody(requestBody, "requestBody");

        DataSource dataSource = getDataSourceOrThrow(datasourceId);
        if (dataSource.getDbType() != DbType.HTTP) {
            throw new ServiceException(
                    Status.DATASOURCE_METADATA_ERROR,
                    "HTTP response parsing is only supported for HTTP data sources");
        }

        ConnectionParam connectionParam = buildConnectionParam(dataSource);
        if (!(connectionParam instanceof HttpConnectionParam httpParam)) {
            throw new ServiceException(
                    Status.DATASOURCE_METADATA_ERROR,
                    "Invalid HTTP connection parameters");
        }

        String path = getRequiredText(requestBody, "path");
        String method = StringUtils.defaultIfBlank(getText(requestBody, "method"), "GET")
                .toUpperCase();
        if (!"GET".equals(method) && !"POST".equals(method)) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "method");
        }

        try {
            Map<String, String> previewValues = RuntimeParameterRenderer.previewValues();
            Map<String, Object> params = asObjectMap(
                    RuntimeParameterRenderer.renderValue(requestBody.get("params"), previewValues));
            String url = HttpRequestSupport.resolveUrl(httpParam.getBaseUrl(), path);
            url = appendQueryParams(url, params);

            Map<String, String> headers = toStringMap(requestBody.get("headers"));
            headers.replaceAll((key, value) ->
                    RuntimeParameterRenderer.renderText(value, previewValues));
            headers = HttpRequestSupport.mergeHeaders(httpParam, headers);
            headers.putIfAbsent("Accept", "application/json");
            if ("POST".equals(method)) {
                headers.putIfAbsent("Content-Type", "application/json");
            }

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1_000, httpParam.getSocketTimeoutMs())))
                    .method(method, bodyPublisher(
                            method, requestBody.get("body"), previewValues));
            headers.forEach(requestBuilder::header);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.max(1_000, httpParam.getConnectTimeoutMs())))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            HttpResponse<String> response = client.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String responseBody = StringUtils.abbreviate(
                        StringUtils.normalizeSpace(response.body()), 1000);
                String detail = StringUtils.isBlank(responseBody) ? "" : ": " + responseBody;
                throw new ServiceException(
                        Status.DATASOURCE_METADATA_ERROR,
                        "HTTP response parsing failed with status " + response.statusCode() + detail);
            }

            JsonNode json = JSONUtils.parseObject(response.body(), JsonNode.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", response.statusCode());
            result.put("body", response.body());
            result.put("json", json);
            return result;
        } catch (ServiceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR, "HTTP response parsing interrupted");
        } catch (Exception e) {
            log.error("Failed to parse HTTP response, datasourceId={}, requestBody={}", datasourceId, requestBody, e);
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR, e.getMessage());
        }
    }

    @Override
    public Integer count(Long datasourceId, Map<String, Object> requestBody) {
        validateDatasourceId(datasourceId);
        validateRequestBody(requestBody, "requestBody");

        DataSource dataSource = getDataSourceOrThrow(datasourceId);
        ConnectionParam connectionParam = buildConnectionParam(dataSource);

        try {
            Map<String, Object> previewRequestBody =
                    renderSqlQueryIfNecessary(dataSource, requestBody);

            return getJdbcCatalog(dataSource, connectionParam).count(previewRequestBody);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to count preview data, datasourceId={}, requestBody={}", datasourceId, requestBody, e);
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR, e.getMessage());
        }
    }

    @Override
    public String buildSqlTemplate(Long datasourceId, Map<String, Object> requestBody) {
        validateDatasourceId(datasourceId);
        validateRequestBody(requestBody, "requestBody");

        String tablePath = getRequiredText(requestBody, KEY_TABLE_PATH);
        getRequiredText(requestBody, KEY_READ_MODE);

        DataSource dataSource = getDataSourceOrThrow(datasourceId);
        ConnectionParam connectionParam = buildConnectionParam(dataSource);
        JdbcCatalog jdbcCatalog = getJdbcCatalog(dataSource, connectionParam);

        Map<String, Object> columnRequest = Map.of(
                KEY_READ_MODE, "table",
                KEY_TABLE_PATH, tablePath,
                KEY_QUERY, ""
        );

        try {
            List<DataSourceTableColumn> columns = jdbcCatalog.listColumns(columnRequest);
            if (columns == null || columns.isEmpty()) {
                throw new ServiceException(Status.DATASOURCE_COLUMN_NOT_FOUND, tablePath);
            }
            return jdbcCatalog.buildSelectAllColumnsSql(tablePath, columns);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to build sql template, datasourceId={}, tablePath={}", datasourceId, tablePath, e);
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR, e.getMessage());
        }
    }

    @Override
    public String resolveSql(Long datasourceId, Map<String, Object> requestBody) {
        validateDatasourceId(datasourceId);
        validateRequestBody(requestBody, "requestBody");

        DataSource dataSource = getDataSourceOrThrow(datasourceId);
        ConnectionParam connectionParam = buildConnectionParam(dataSource);

        try {
            Map<String, Object> resolvedRequestBody =
                    renderSqlQueryIfNecessary(dataSource, requestBody);

            String query = getRequiredText(resolvedRequestBody, KEY_QUERY);

            return getJdbcCatalog(dataSource, connectionParam).resolveSqlVariables(query);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to resolve sql, datasourceId={}, requestBody={}", datasourceId, requestBody, e);
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR, e.getMessage());
        }
    }

    private Map<String, Object> renderSqlQueryIfNecessary(
            DataSource dataSource,
            Map<String, Object> requestBody) {

        String readMode = getText(requestBody, KEY_READ_MODE);
        if (!READ_MODE_SQL.equalsIgnoreCase(readMode)) {
            return requestBody;
        }

        String query = getText(requestBody, KEY_QUERY);
        if (StringUtils.isBlank(query)) {
            return requestBody;
        }

        if (!containsTimeVariable(query)) {
            return requestBody;
        }

        DataSourceHoconBuilder hoconBuilder = getPreviewHoconBuilder(dataSource, requestBody);
        JobScheduleConfig scheduleConfig = buildScheduleConfig(requestBody);

        String renderedQuery = timeVariableJdbcSqlRenderService.renderSql(
                query,
                hoconBuilder,
                scheduleConfig
        );

        Map<String, Object> nextRequestBody = new HashMap<>(requestBody);
        nextRequestBody.put(KEY_QUERY, renderedQuery);

        log.info("Rendered preview sql, originalSql={}, renderedSql={}", query, renderedQuery);

        return nextRequestBody;
    }

    private DataSourceHoconBuilder getPreviewHoconBuilder(
            DataSource dataSource,
            Map<String, Object> requestBody) {

        String pluginName = resolvePluginName(dataSource, requestBody);

        try {
            DataSourceProcessor processor =
                    DataSourceUtils.getDatasourceProcessor(dataSource.getDbType());

            return processor.getQueryBuilder(pluginName);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get preview hocon builder, datasourceId={}, dbType={}, pluginName={}",
                    dataSource.getId(), dataSource.getDbType(), pluginName, e);
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR, e.getMessage());
        }
    }

    private String resolvePluginName(DataSource dataSource, Map<String, Object> requestBody) {
        String pluginName = getText(requestBody, KEY_PLUGIN_NAME);
        if (StringUtils.isNotBlank(pluginName)) {
            return pluginName;
        }

        String connectorType = getText(requestBody, KEY_CONNECTOR_TYPE);
        if (StringUtils.isNotBlank(connectorType)) {
            return connectorType;
        }

        /*
         * 兜底逻辑：
         * 前端预览接口当前不一定传 pluginName。
         * JDBC 插件一般是 JDBC-MYSQL / JDBC-POSTGRESQL / JDBC-ORACLE 这种形式。
         *
         * 如果你的 DbType 与插件名不是这个规则，建议前端 requestBody 明确传 pluginName。
         */
        return "JDBC-" + String.valueOf(dataSource.getDbType()).toUpperCase();
    }

    private JobScheduleConfig buildScheduleConfig(Map<String, Object> requestBody) {
        JobScheduleConfig scheduleConfig = new JobScheduleConfig();
        scheduleConfig.setParamsList(buildScheduleParamItems(requestBody.get(KEY_PARAMS_LIST)));
        return scheduleConfig;
    }

    @SuppressWarnings("unchecked")
    private List<JobScheduleConfig.ScheduleParamItem> buildScheduleParamItems(Object rawParamsList) {
        List<JobScheduleConfig.ScheduleParamItem> result = new ArrayList<>();

        if (!(rawParamsList instanceof List) || ((List<?>) rawParamsList).isEmpty()) {
            return result;
        }

        List<?> paramsList = (List<?>) rawParamsList;

        for (Object rawItem : paramsList) {
            if (!(rawItem instanceof Map)) {
                continue;
            }

            Map<?, ?> rawMap = (Map<?, ?>) rawItem;

            String paramName = getText(rawMap, KEY_PARAM_NAME);
            String paramValue = getText(rawMap, KEY_PARAM_VALUE);

            if (StringUtils.isBlank(paramName)) {
                continue;
            }

            JobScheduleConfig.ScheduleParamItem item =
                    new JobScheduleConfig.ScheduleParamItem();

            /*
             * 注意：
             * 这里 paramName 实际保存的是 TimeVariable 的数据库 ID。
             * TimeVariableJdbcSqlRenderService 里会用这个 ID 去判断：
             * SQL 中的 ${start_time} 是否在 paramsList 中配置过。
             */
            item.setParamName(paramName);
            item.setParamValue(paramValue);

            result.add(item);
        }

        return result;
    }

    private boolean containsTimeVariable(String query) {
        return query.contains("${");
    }

    private DataSource getDataSourceOrThrow(Long datasourceId) {
        validateDatasourceId(datasourceId);

        DataSource dataSource = dataSourceService.selectById(datasourceId);
        if (dataSource == null) {
            log.warn("Datasource not found, datasourceId={}", datasourceId);
            throw new ServiceException(Status.DATASOURCE_NOT_EXIST);
        }
        return dataSource;
    }

    private ConnectionParam buildConnectionParam(DataSource dataSource) {
        try {
            return DataSourceUtils.buildConnectionParams(
                    dataSource.getDbType(),
                    dataSource.getConnectionParams()
            );
        } catch (Exception e) {
            log.error("Failed to build connection param, datasourceId={}", dataSource.getId(), e);
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR, e.getMessage());
        }
    }

    private DataSourceCatalog getCatalog(DataSource dataSource, ConnectionParam connectionParam) {
        try {
            return DataSourceUtils.getDatasourceProcessor(dataSource.getDbType())
                    .getCatalog(connectionParam)
                    .orElseThrow(() -> new IllegalArgumentException(
                            dataSource.getDbType() + " does not support catalog operations"));
        } catch (Exception e) {
            log.error("Failed to get datasource catalog, datasourceId={}", dataSource.getId(), e);
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR, e.getMessage());
        }
    }

    private HttpRequest.BodyPublisher bodyPublisher(
            String method, Object rawBody, Map<String, String> previewValues) {
        if ("GET".equals(method)) {
            return HttpRequest.BodyPublishers.noBody();
        }

        Object renderedBody = rawBody instanceof String
                ? RuntimeParameterRenderer.renderJsonBody((String) rawBody, previewValues)
                : RuntimeParameterRenderer.renderValue(rawBody, previewValues);
        String body = renderedBody == null ? "" : renderedBody instanceof String
                ? (String) renderedBody
                : JSONUtils.toJsonString(renderedBody);
        return HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
    }

    private String appendQueryParams(String url, Map<String, Object> params) {
        if (params.isEmpty()) {
            return url;
        }

        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (StringUtils.isBlank(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            query.append('=');
            query.append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        if (query.length() == 0) {
            return url;
        }

        StringBuilder result = new StringBuilder(url);
        result.append(url.contains("?") ? (url.endsWith("?") || url.endsWith("&") ? "" : "&") : "?");
        result.append(query);
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asObjectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    private Map<String, String> toStringMap(Object raw) {
        Map<String, Object> source = asObjectMap(raw);
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value != null) {
                result.put(key, String.valueOf(value));
            }
        });
        return result;
    }

    private JdbcCatalog getJdbcCatalog(DataSource dataSource, ConnectionParam connectionParam) {
        DataSourceCatalog catalog = getCatalog(dataSource, connectionParam);
        if (!(catalog instanceof JdbcCatalog)) {
            throw new ServiceException(
                    Status.DATASOURCE_METADATA_ERROR,
                    dataSource.getDbType() + " does not support column, SQL, count, or preview operations");
        }
        return (JdbcCatalog) catalog;
    }

    private void validateDatasourceId(Long datasourceId) {
        if (datasourceId == null || datasourceId <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "datasourceId");
        }
    }

    private void validateRequestBody(Map<String, Object> requestBody, String fieldName) {
        if (MapUtils.isEmpty(requestBody)) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, fieldName);
        }
    }

    private String getRequiredText(Map<String, Object> requestBody, String key) {
        String value = getText(requestBody, key);
        if (StringUtils.isBlank(value)) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, key);
        }
        return value;
    }

    private String getText(Map<?, ?> map, String key) {
        if (map == null || !map.containsKey(key)) {
            return null;
        }

        Object value = map.get(key);
        if (value == null) {
            return null;
        }

        return String.valueOf(value).trim();
    }

    private boolean matchExactTable(String tableName, String[] exactNames) {
        for (String exact : exactNames) {
            if (tableName.equals(exact.trim())) {
                return true;
            }
        }
        return false;
    }

    private OptionVO toOption(String value) {
        OptionVO optionVO = new OptionVO();
        optionVO.setLabel(value);
        optionVO.setValue(value);
        return optionVO;
    }
}
