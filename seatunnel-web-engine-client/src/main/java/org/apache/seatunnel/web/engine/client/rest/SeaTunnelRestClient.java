package org.apache.seatunnel.web.engine.client.rest;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.engine.client.exceptions.SeaTunnelClientException;
import org.apache.seatunnel.web.engine.client.modal.SeaTunnelClientAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@SuppressWarnings({"unchecked", "rawtypes"})
@Service
public class SeaTunnelRestClient {

    private static final String DEFAULT_FINISHED_JOB_STATE = "UNKNOWABLE";
    private static final String DEFAULT_CONFIG_FILE_NAME = "job.conf";

    private final RestTemplate restTemplate;
    private final RestTemplate jobSubmitRestTemplate;
    private final RestTemplate noRedirectRestTemplate;
    private final SeaTunnelClientResolver seatunnelClientResolver;

    /** Validated Engine metadata for one node-local job log file. */
    public record NodeLogFile(String node, String logLink, String logName) {
    }

    @Autowired
    public SeaTunnelRestClient(@Qualifier("seaTunnelRestTemplate") RestTemplate restTemplate,
                               @Qualifier("seaTunnelJobSubmitRestTemplate") RestTemplate jobSubmitRestTemplate,
                               SeaTunnelClientResolver seatunnelClientResolver,
                               SeaTunnelClientProperties properties) {
        this(restTemplate, jobSubmitRestTemplate, seatunnelClientResolver,
                createNoRedirectRestTemplate(properties));
    }

    private SeaTunnelRestClient(RestTemplate restTemplate,
                                RestTemplate jobSubmitRestTemplate,
                                SeaTunnelClientResolver seatunnelClientResolver,
                                RestTemplate noRedirectRestTemplate) {
        this.restTemplate = restTemplate;
        this.jobSubmitRestTemplate = jobSubmitRestTemplate;
        this.seatunnelClientResolver = seatunnelClientResolver;
        this.noRedirectRestTemplate = noRedirectRestTemplate;
    }

    public SeaTunnelRestClient(RestTemplate restTemplate,
                               SeaTunnelClientResolver seatunnelClientResolver) {
        this(restTemplate, restTemplate, seatunnelClientResolver,
                createNoRedirectRestTemplate(new SeaTunnelClientProperties()));
    }

    public SeaTunnelRestClient(RestTemplate restTemplate,
                               RestTemplate jobSubmitRestTemplate,
                               SeaTunnelClientResolver seatunnelClientResolver) {
        this(restTemplate, jobSubmitRestTemplate, seatunnelClientResolver,
                createNoRedirectRestTemplate(new SeaTunnelClientProperties()));
    }

    private static RestTemplate createNoRedirectRestTemplate(SeaTunnelClientProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        return new RestTemplate(factory);
    }

    /* ===================== URL ===================== */

    private String url(Long clientId, String path) {
        String baseApiUrl = seatunnelClientResolver.resolveBaseApiUrl(clientId);

        if (baseApiUrl == null || baseApiUrl.trim().isEmpty()) {
            throw new SeaTunnelClientException(
                    "SeaTunnel client baseUrl is empty",
                    -1,
                    "",
                    null
            );
        }

        baseApiUrl = trimEndSlash(baseApiUrl.trim());

        if (path == null || path.trim().isEmpty()) {
            return baseApiUrl;
        }

        path = path.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        return baseApiUrl + path;
    }

    private String trimEndSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /* ===================== Headers ===================== */

    private HttpHeaders jsonHeaders(Long clientId) {
        return headers(clientId, MediaType.APPLICATION_JSON);
    }

    private HttpHeaders textHeaders(Long clientId) {
        return headers(clientId, MediaType.TEXT_PLAIN);
    }

    private HttpHeaders multipartHeaders(Long clientId) {
        return headers(clientId, MediaType.MULTIPART_FORM_DATA);
    }

    private HttpHeaders octetStreamHeaders(Long clientId) {
        return headers(clientId, MediaType.APPLICATION_OCTET_STREAM);
    }

    private HttpHeaders getHeaders(Long clientId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        applyBasicAuth(clientId, headers);
        return headers;
    }

    private HttpHeaders headers(Long clientId, MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        applyBasicAuth(clientId, headers);
        return headers;
    }

    private void applyBasicAuth(Long clientId, HttpHeaders headers) {
        if (clientId == null) {
            return;
        }

        SeaTunnelClientAuth auth = seatunnelClientResolver.resolveAuth(clientId);

        if (auth == null) {
            return;
        }

        if (!Boolean.TRUE.equals(auth.getAuthEnabled())) {
            return;
        }

        if (isBlank(auth.getUsername()) || isBlank(auth.getPassword())) {
            return;
        }

        headers.setBasicAuth(
                auth.getUsername().trim(),
                auth.getPassword(),
                StandardCharsets.UTF_8
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /* ===================== Common Request ===================== */

    private <T> T get(Long clientId, String requestUrl, Class<T> responseType, String hint) {
        try {
            ResponseEntity<T> response = restTemplate.exchange(
                    requestUrl,
                    HttpMethod.GET,
                    new HttpEntity<Void>(null, getHeaders(clientId)),
                    responseType
            );
            return response.getBody();
        } catch (Exception e) {
            throw wrap(e, hint);
        }
    }

    private <T> T post(Long clientId,
                       String requestUrl,
                       Object body,
                       HttpHeaders headers,
                       Class<T> responseType,
                       String hint) {
        try {
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            ResponseEntity<T> response = restTemplate.exchange(
                    requestUrl,
                    HttpMethod.POST,
                    entity,
                    responseType
            );
            return response.getBody();
        } catch (Exception e) {
            throw wrap(e, hint);
        }
    }

    private RuntimeException wrap(Exception e, String hint) {
        if (e instanceof HttpStatusCodeException) {
            HttpStatusCodeException he = (HttpStatusCodeException) e;
            return new SeaTunnelClientException(
                    hint,
                    he.getRawStatusCode(),
                    safe(he.getResponseBodyAsString()),
                    he
            );
        }

        return new SeaTunnelClientException(
                hint,
                -1,
                "",
                e
        );
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    /* ===================== GET ===================== */

    public Map overview(Long clientId, Map<String, String> tags) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url(clientId, "/overview"));
            appendQueryParams(builder, tags);

            return get(
                    clientId,
                    builder.build(true).toUriString(),
                    Map.class,
                    "GET /overview failed"
            );
        } catch (Exception e) {
            throw wrap(e, "GET /overview failed");
        }
    }

    /**
     * 保留这个重载，适合没有 clientId 的临时探活场景。
     *
     * 注意：这个方法没有 clientId，所以无法从数据库读取账号密码。
     * 如果 Zeta Engine 开启了 Basic Auth，建议优先使用 overview(Long clientId, Map<String, String> tags)。
     */
    public Map overview(String baseUrl, Map<String, String> tags) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl);
            appendQueryParams(builder, tags);

            ResponseEntity<Map> response = restTemplate.exchange(
                    builder.build(true).toUri(),
                    HttpMethod.GET,
                    new HttpEntity<Void>(null, new HttpHeaders()),
                    Map.class
            );

            return response.getBody();
        } catch (Exception e) {
            throw wrap(e, "GET /overview failed");
        }
    }

    public Map overview(String baseUrl, String contextPath, Map<String, String> tags, SeaTunnelClientAuth auth) {
        try {
            String finalUrl = buildFullUrl(baseUrl, contextPath, "/overview");

            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(finalUrl);
            appendQueryParams(builder, tags);

            ResponseEntity<Map> response = restTemplate.exchange(
                    builder.build(true).toUri(),
                    HttpMethod.GET,
                    new HttpEntity<Void>(null, getHeaders(auth)),
                    Map.class
            );

            return response.getBody();
        } catch (Exception e) {
            throw wrap(e, "GET /overview failed");
        }
    }

    /**
     * 这是个重载，适合没有 clientId 的临时探活场景。
     * @param baseUrl
     * @param contextPath
     * @param auth 认证数据
     * @return 多节点数据，含所有master + workers
     */
    public List systemMonitoringInformation(String baseUrl, String contextPath, SeaTunnelClientAuth auth) {
        try {
            String finalUrl = buildFullUrl(baseUrl, contextPath, "/system-monitoring-information");

            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(finalUrl);

            ResponseEntity<List> response = restTemplate.exchange(
                    builder.build(true).toUri(),
                    HttpMethod.GET,
                    new HttpEntity<Void>(null, getHeaders(auth)),
                    List.class
            );

            return response.getBody();
        } catch (Exception e) {
            throw wrap(e, "GET /system-monitoring-information failed");
        }
    }

    /**
     * 拼接基础 URL、上下文路径和具体接口路径
     *
     * @param baseUrl     基础 URL
     * @param contextPath 上下文路径
     * @param apiPath     具体的接口路径
     * @return 拼接完成的完整 URL
     */
    private String buildFullUrl(String baseUrl, String contextPath, String apiPath) {
        String url = StringUtils.removeEnd(baseUrl, "/");

        if (StringUtils.isNotBlank(contextPath)) {
            url = url + "/" + StringUtils.removeStart(contextPath, "/");
        }

        return url + StringUtils.prependIfMissing(apiPath, "/");
    }

    private HttpHeaders getHeaders(SeaTunnelClientAuth auth) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        applyBasicAuth(auth, headers);
        return headers;
    }

    private void applyBasicAuth(SeaTunnelClientAuth auth, HttpHeaders headers) {
        if (auth == null) {
            return;
        }

        if (!Boolean.TRUE.equals(auth.getAuthEnabled())) {
            return;
        }

        if (isBlank(auth.getUsername()) || isBlank(auth.getPassword())) {
            return;
        }

        headers.setBasicAuth(
                auth.getUsername().trim(),
                auth.getPassword(),
                StandardCharsets.UTF_8
        );
    }

    public List runningJobs(Long clientId) {
        return get(
                clientId,
                url(clientId, "/running-jobs"),
                List.class,
                "GET /running-jobs failed"
        );
    }

    public Map jobInfo(Long clientId, String jobId) {
        return get(
                clientId,
                url(clientId, "/job-info/" + jobId),
                Map.class,
                "GET /job-info/{jobId} failed"
        );
    }

    public List finishedJobs(Long clientId, String state) {
        if (isBlank(state)) {
            state = DEFAULT_FINISHED_JOB_STATE;
        }

        return get(
                clientId,
                url(clientId, "/finished-jobs/" + state.trim()),
                List.class,
                "GET /finished-jobs/{state} failed"
        );
    }

    public List systemMonitoringInformation(Long clientId) {
        return get(
                clientId,
                url(clientId, "/system-monitoring-information"),
                List.class,
                "GET /system-monitoring-information failed"
        );
    }

    public String logs(Long clientId, String jobIdOrNull, String formatOrNull) {
        try {
            String fullPath = String.format("job-%s.log", jobIdOrNull);
            String path = jobIdOrNull == null ? "/log" : "/log/" + fullPath;

            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url(clientId, path));
            if (!isBlank(formatOrNull)) {
                builder.queryParam("format", formatOrNull.trim());
            }

            return get(
                    clientId,
                    builder.build(true).toUriString(),
                    String.class,
                    "GET /log failed"
            );
        } catch (Exception e) {
            throw wrap(e, "GET /log failed");
        }
    }

    public String jobLogs(Long clientId, String engineJobId, String formatOrNull) {
        if (clientId == null) {
            throw new IllegalArgumentException("clientId cannot be empty");
        }

        if (engineJobId == null) {
            throw new IllegalArgumentException("engineJobId cannot be empty");
        }

        return logs(clientId, engineJobId, formatOrNull);
    }

    public String nodeLogs(Long clientId) {
        return get(
                clientId,
                url(clientId, "/log"),
                String.class,
                "GET /log failed"
        );
    }

    /**
     * Reads a named Engine log file from all nodes. This is useful for failures
     * that occur before SeaTunnel creates the per-job log file.
     */
    public String clusterLogFile(Long clientId, String fileName) {
        if (clientId == null) {
            throw new IllegalArgumentException("clientId cannot be empty");
        }
        String safeFileName = StringUtils.trimToEmpty(fileName);
        if (safeFileName.isEmpty() || safeFileName.contains("/") || safeFileName.contains("\\")
                || ".".equals(safeFileName) || "..".equals(safeFileName)) {
            throw new IllegalArgumentException("fileName must be a log file name");
        }

        String encodedFileName = UriUtils.encodePathSegment(safeFileName, StandardCharsets.UTF_8);
        String requestUrl = UriComponentsBuilder.fromHttpUrl(url(clientId, "/logs/" + encodedFileName))
                .build(true)
                .toUriString();
        return get(
                clientId,
                requestUrl,
                String.class,
                "GET /logs/{fileName} failed"
        );
    }

    /**
     * Lists the per-node Engine log files for a job. The Engine supplies absolute log links;
     * each link is validated against its node authority and the configured REST context path
     * before it can be used by {@link #writeJobLogFile(Long, String, NodeLogFile, OutputStream)}.
     */
    public List<NodeLogFile> jobLogFiles(Long clientId, String engineJobId) {
        requireClientId(clientId);
        String safeJobId = requireSafeJobId(engineJobId);
        String fileName = "job-" + safeJobId + ".log";
        String encodedJobId = UriUtils.encodePathSegment(safeJobId, StandardCharsets.UTF_8);
        String requestUrl = UriComponentsBuilder.fromHttpUrl(url(clientId, "/logs/" + encodedJobId))
                .queryParam("format", "json")
                .build(true)
                .toUriString();

        ResponseEntity<List> response = noRedirectRestTemplate.exchange(
                requestUrl,
                HttpMethod.GET,
                new HttpEntity<>(getHeaders(clientId)),
                List.class
        );
        List<?> metadata = response.getBody();
        if (metadata == null || metadata.isEmpty()) {
            return Collections.emptyList();
        }
        if (metadata.size() > 256) {
            throw new IllegalArgumentException("Engine returned too many job log nodes");
        }

        Set<String> registeredNodeHosts = registeredNodeHosts(clientId);
        Map<String, NodeLogFile> uniqueFiles = new LinkedHashMap<>();
        for (Object item : metadata) {
            if (!(item instanceof Map<?, ?> fields)) {
                throw new IllegalArgumentException("Engine returned invalid job log node metadata");
            }
            String node = stringField(fields, "node");
            String logLink = stringField(fields, "logLink");
            String logName = stringField(fields, "logName");
            NodeLogFile file = validateNodeLogFile(
                    clientId, safeJobId, node, logLink, logName, registeredNodeHosts);
            NodeLogFile previous = uniqueFiles.putIfAbsent(file.node(), file);
            if (previous != null && !previous.equals(file)) {
                throw new IllegalArgumentException("Engine returned conflicting job log metadata for a node");
            }
        }
        return List.copyOf(uniqueFiles.values());
    }

    /** Streams one already-listed node log without buffering it as a String or following redirects. */
    public long writeJobLogFile(Long clientId,
                                String engineJobId,
                                NodeLogFile file,
                                OutputStream output) {
        requireClientId(clientId);
        if (output == null) {
            throw new IllegalArgumentException("output cannot be empty");
        }
        // jobLogFiles already checked this node against the Engine's membership array;
        // recheck the immutable authority and path here without another topology request.
        NodeLogFile validated = validateNodeLogFile(
                clientId,
                requireSafeJobId(engineJobId),
                file == null ? null : file.node(),
                file == null ? null : file.logLink(),
                file == null ? null : file.logName(),
                null
        );
        return streamNoRedirect(clientId, validated.logLink(), output, "GET Engine node job log failed");
    }

    /** Streams the configured Engine endpoint's log file, used when node links are unreachable. */
    public long writeMasterJobLogFile(Long clientId, String engineJobId, OutputStream output) {
        requireClientId(clientId);
        if (output == null) {
            throw new IllegalArgumentException("output cannot be empty");
        }
        String fileName = "job-" + requireSafeJobId(engineJobId) + ".log";
        String encodedFileName = UriUtils.encodePathSegment(fileName, StandardCharsets.UTF_8);
        String requestUrl = UriComponentsBuilder.fromHttpUrl(url(clientId, "/logs/" + encodedFileName))
                .build(true)
                .toUriString();
        return streamNoRedirect(clientId, requestUrl, output, "GET configured Engine job log fallback failed");
    }

    private long streamNoRedirect(Long clientId, String requestUrl, OutputStream output, String errorMessage) {
        try {
            Long copied = noRedirectRestTemplate.execute(
                    requestUrl,
                    HttpMethod.GET,
                    request -> request.getHeaders().putAll(logHeaders(clientId)),
                    response -> {
                        try (InputStream input = response.getBody()) {
                            return input.transferTo(output);
                        }
                    }
            );
            return copied == null ? 0L : copied;
        } catch (Exception e) {
            throw wrap(e, errorMessage);
        }
    }

    private NodeLogFile validateNodeLogFile(Long clientId,
                                            String engineJobId,
                                            String node,
                                            String logLink,
                                            String logName,
                                            Set<String> registeredNodeHosts) {
        String expectedFileName = "job-" + engineJobId + ".log";
        if (isBlank(node) || isBlank(logLink) || !expectedFileName.equals(logName)) {
            throw new IllegalArgumentException("Engine returned incomplete job log node metadata");
        }

        URI baseUri = URI.create(url(clientId, ""));
        URI nodeUri;
        URI linkUri;
        try {
            nodeUri = URI.create(baseUri.getScheme() + "://" + node.trim());
            linkUri = URI.create(logLink.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Engine returned malformed job log node metadata", e);
        }

        String expectedPath = configuredContextPath(baseUri) + "/logs/"
                + UriUtils.encodePathSegment(expectedFileName, StandardCharsets.UTF_8);
        if (nodeUri.getHost() == null
                || nodeUri.getUserInfo() != null
                || nodeUri.getPort() < 1
                || (registeredNodeHosts != null
                        && !registeredNodeHosts.contains(nodeUri.getHost().toLowerCase(Locale.ROOT)))
                || nodeUri.getRawPath() != null && !nodeUri.getRawPath().isEmpty()
                || nodeUri.getRawQuery() != null
                || nodeUri.getRawFragment() != null
                || !linkUri.isAbsolute()
                || !StringUtils.equalsIgnoreCase(baseUri.getScheme(), linkUri.getScheme())
                || linkUri.getUserInfo() != null
                || linkUri.getHost() == null
                || !StringUtils.equalsIgnoreCase(nodeUri.getHost(), linkUri.getHost())
                || nodeUri.getPort() != linkUri.getPort()
                || !expectedPath.equals(linkUri.getRawPath())
                || linkUri.getRawQuery() != null
                || linkUri.getRawFragment() != null) {
            throw new IllegalArgumentException("Engine returned untrusted job log node metadata");
        }
        return new NodeLogFile(node.trim(), linkUri.toString(), logName);
    }

    /**
     * SeaTunnel reports cluster membership with its Hazelcast port (5801 by default),
     * while job log links use the REST port (8080); membership is therefore matched by host.
     */
    private Set<String> registeredNodeHosts(Long clientId) {
        String requestUrl = url(clientId, "/system-monitoring-information");
        ResponseEntity<List> response = noRedirectRestTemplate.exchange(
                requestUrl,
                HttpMethod.GET,
                new HttpEntity<>(getHeaders(clientId)),
                List.class
        );
        List<?> nodes = response.getBody();
        if (nodes == null || nodes.isEmpty() || nodes.size() > 256) {
            throw new IllegalArgumentException("Engine returned invalid cluster node metadata");
        }

        Set<String> hosts = new LinkedHashSet<>();
        for (Object node : nodes) {
            if (!(node instanceof Map<?, ?> fields)) {
                throw new IllegalArgumentException("Engine returned invalid cluster node metadata");
            }
            String host = stringField(fields, "host");
            if (isBlank(host)) {
                throw new IllegalArgumentException("Engine returned a cluster node without a host");
            }
            hosts.add(host.toLowerCase(Locale.ROOT));
        }
        return hosts;
    }

    private String configuredContextPath(URI baseUri) {
        String rawPath = baseUri.getRawPath();
        if (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath)) {
            return "";
        }
        return rawPath.endsWith("/") ? rawPath.substring(0, rawPath.length() - 1) : rawPath;
    }

    private String stringField(Map<?, ?> fields, String name) {
        Object value = fields.get(name);
        return value == null ? null : String.valueOf(value).trim();
    }

    private String requireSafeJobId(String engineJobId) {
        if (engineJobId == null || !engineJobId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("engineJobId must be an identifier");
        }
        return engineJobId;
    }

    private void requireClientId(Long clientId) {
        if (clientId == null) {
            throw new IllegalArgumentException("clientId cannot be empty");
        }
    }

    private HttpHeaders logHeaders(Long clientId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM));
        applyBasicAuth(clientId, headers);
        return headers;
    }

    public String metrics(Long clientId, boolean openMetrics) {
        String path = openMetrics ? "/openmetrics" : "/metrics";

        return get(
                clientId,
                url(clientId, path),
                String.class,
                "GET /metrics failed"
        );
    }

    /* ===================== POST ===================== */

    public Map submitJobText(Long clientId,
                             String configText,
                             String format,
                             String jobId,
                             String jobName,
                             Boolean isStartWithSavePoint) {
        try {
            if (isBlank(format)) {
                format = "json";
            }

            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url(clientId, "/submit-job"))
                    .queryParam("format", format.trim());

            appendSubmitJobParams(builder, jobId, jobName, isStartWithSavePoint);

            return post(
                    clientId,
                    builder.build(true).toUriString(),
                    configText == null ? "" : configText,
                    textHeaders(clientId),
                    Map.class,
                    "POST /submit-job(text) failed"
            );
        } catch (Exception e) {
            throw wrap(e, "POST /submit-job(text) failed");
        }
    }

    public Map submitJobJson(Long clientId,
                             Object configJsonObject,
                             String jobId,
                             String jobName,
                             Boolean isStartWithSavePoint) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url(clientId, "/submit-job"))
                    .queryParam("format", "json");

            appendSubmitJobParams(builder, jobId, jobName, isStartWithSavePoint);

            return post(
                    clientId,
                    builder.build(true).toUriString(),
                    configJsonObject,
                    jsonHeaders(clientId),
                    Map.class,
                    "POST /submit-job(json) failed"
            );
        } catch (Exception e) {
            throw wrap(e, "POST /submit-job(json) failed");
        }
    }

    public Map submitJobUpload(Long clientId, byte[] fileBytes, String filename) {
        return submitJobUpload(clientId, fileBytes, filename, null, null, null);
    }

    public Map submitJobUpload(Long clientId,
                               byte[] fileBytes,
                               String filename,
                               String jobId,
                               String jobName,
                               Boolean isStartWithSavePoint) {
        return submitJobUpload(clientId, fileBytes, filename, jobId, jobName, isStartWithSavePoint, null);
    }

    public Map submitJobUpload(Long clientId,
                               byte[] fileBytes,
                               String filename,
                               String jobId,
                               String jobName,
                               Boolean isStartWithSavePoint,
                               String restoreSourceJobId) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

            ByteArrayResource resource = new ByteArrayResource(fileBytes == null ? new byte[0] : fileBytes) {
                @Override
                public String getFilename() {
                    return isBlank(filename) ? DEFAULT_CONFIG_FILE_NAME : filename.trim();
                }
            };

            body.add("config_file", resource);

            HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(
                    body,
                    multipartHeaders(clientId)
            );

            UriComponentsBuilder builder =
                    UriComponentsBuilder.fromHttpUrl(url(clientId, "/submit-job/upload"));

            appendSubmitJobParams(builder, jobId, jobName, isStartWithSavePoint);
            if (!isBlank(restoreSourceJobId)) {
                builder.queryParam("restoreMode", "SAVEPOINT")
                        .queryParam("restoreSourceJobId", restoreSourceJobId.trim());
            }

            ResponseEntity<Map> response = jobSubmitRestTemplate.exchange(
                    builder.build(true).toUriString(),
                    HttpMethod.POST,
                    request,
                    Map.class
            );

            return response.getBody();
        } catch (Exception e) {
            throw wrap(e, "POST /submit-job/upload failed");
        }
    }

    /**
     * Uploads a JDBC driver jar to the engine. The engine stores it and pushes it to every cluster
     * member; the returned path is what a job has to declare in {@code driver_location}.
     */
    public String uploadDriverJar(Long clientId, byte[] jarBytes, String fileName) {
        try {
            // The name comes from a file on disk, so it has to be encoded before it becomes a query
            // parameter: a name with '&' or '#' would otherwise split the request.
            UriComponentsBuilder builder =
                    UriComponentsBuilder.fromHttpUrl(url(clientId, "/driver-jar/upload"))
                            .queryParam(
                                    "fileName",
                                    UriUtils.encodeQueryParam(
                                            fileName == null ? "" : fileName,
                                            StandardCharsets.UTF_8));

            Map response = post(
                    clientId,
                    builder.build(true).toUriString(),
                    jarBytes == null ? new byte[0] : jarBytes,
                    octetStreamHeaders(clientId),
                    Map.class,
                    "POST /driver-jar/upload failed"
            );

            Object path = response == null ? null : response.get("path");
            if (path == null || isBlank(String.valueOf(path))) {
                throw new SeaTunnelClientException(
                        "The engine did not return a driver jar path",
                        -1,
                        "",
                        null
                );
            }

            return String.valueOf(path);
        } catch (SeaTunnelClientException e) {
            throw e;
        } catch (Exception e) {
            throw wrap(e, "POST /driver-jar/upload failed");
        }
    }

    public List submitJobsBatch(Long clientId, List jobConfigs) {
        return post(
                clientId,
                url(clientId, "/submit-jobs"),
                jobConfigs,
                jsonHeaders(clientId),
                List.class,
                "POST /submit-jobs failed"
        );
    }

    public Map stopJob(Long clientId, String jobId, boolean isStopWithSavePoint) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("isStopWithSavePoint", isStopWithSavePoint);

        return post(
                clientId,
                url(clientId, "/stop-job"),
                body,
                jsonHeaders(clientId),
                Map.class,
                "POST /stop-job failed"
        );
    }

    public Map checkpointOverview(Long clientId, Long jobId) {
        if (clientId == null) {
            throw new IllegalArgumentException("clientId cannot be empty");
        }

        if (jobId == null) {
            throw new IllegalArgumentException("jobId cannot be empty");
        }

        return get(
                clientId,
                url(clientId, "/jobs/checkpoints/" + jobId),
                Map.class,
                "GET /jobs/checkpoints/{jobId} failed"
        );
    }

    public List checkpointHistory(Long clientId,
                                  Long jobId,
                                  Long pipelineId,
                                  Integer limit,
                                  String status) {
        if (clientId == null) {
            throw new IllegalArgumentException("clientId cannot be empty");
        }

        if (jobId == null) {
            throw new IllegalArgumentException("jobId cannot be empty");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(
                url(clientId, "/jobs/checkpoints/history/" + jobId)
        );

        if (pipelineId != null) {
            builder.queryParam("pipelineId", pipelineId);
        }

        if (limit != null) {
            builder.queryParam("limit", limit);
        }

        if (!isBlank(status)) {
            builder.queryParam("status", status.trim());
        }

        return get(
                clientId,
                builder.build(true).toUriString(),
                List.class,
                "GET /jobs/checkpoints/history/{jobId} failed"
        );
    }

    public List stopJobsBatch(Long clientId, List<Map<String, Object>> items) {
        return post(
                clientId,
                url(clientId, "/stop-jobs"),
                items,
                jsonHeaders(clientId),
                List.class,
                "POST /stop-jobs failed"
        );
    }

    /* ===================== Query Params ===================== */

    private void appendQueryParams(UriComponentsBuilder builder, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return;
        }

        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!isBlank(entry.getKey()) && entry.getValue() != null) {
                builder.queryParam(entry.getKey().trim(), entry.getValue());
            }
        }
    }

    private void appendSubmitJobParams(UriComponentsBuilder builder,
                                       String jobId,
                                       String jobName,
                                       Boolean isStartWithSavePoint) {
        if (!isBlank(jobId)) {
            builder.queryParam("jobId", jobId.trim());
        }

        if (!isBlank(jobName)) {
            builder.queryParam("jobName", jobName.trim());
        }

        if (isStartWithSavePoint != null) {
            builder.queryParam("isStartWithSavePoint", isStartWithSavePoint);
        }
    }
}
