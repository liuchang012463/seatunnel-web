package org.apache.seatunnel.plugin.datasource.s3.client;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.s3.param.ObjectStorageConnectionParam;
import org.apache.seatunnel.plugin.datasource.s3.param.ObjectStorageCredentialMode;
import org.apache.seatunnel.web.spi.bean.vo.FileEntryVO;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class S3ObjectStorageClient implements ObjectStorageClient {

    @FunctionalInterface
    interface ClientFactory {
        AmazonS3 create(ObjectStorageConnectionParam param);
    }

    private final ClientFactory clientFactory;

    public S3ObjectStorageClient() {
        this(S3ObjectStorageClient::buildClient);
    }

    S3ObjectStorageClient(ClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public boolean checkDataSourceConnectivity(ConnectionParam connectionParam) {
        ObjectStorageConnectionParam param = requireParam(connectionParam);
        AmazonS3 client = clientFactory.create(param);
        try {
            ListObjectsV2Request request = new ListObjectsV2Request()
                    .withBucketName(param.getBucket())
                    .withPrefix(ObjectStoragePathUtils.toDirectoryPrefix(param.getBasePath()))
                    .withMaxKeys(1);
            client.listObjectsV2(request);
            return true;
        } finally {
            client.shutdown();
        }
    }

    @Override
    public List<FileEntryVO> listEntries(ObjectStorageConnectionParam connectionParam, String path) {
        ObjectStorageConnectionParam param = requireParam(connectionParam);
        String resolved = ObjectStoragePathUtils.resolveWithinBase(param.getBasePath(), path);
        String prefix = ObjectStoragePathUtils.toDirectoryPrefix(resolved);
        AmazonS3 client = clientFactory.create(param);
        try {
            Map<String, FileEntryVO> entries = new LinkedHashMap<>();
            String continuationToken = null;
            do {
                ListObjectsV2Request request = new ListObjectsV2Request()
                        .withBucketName(param.getBucket())
                        .withPrefix(prefix)
                        .withDelimiter("/")
                        .withContinuationToken(continuationToken);
                ListObjectsV2Result page = client.listObjectsV2(request);
                appendDirectories(entries, prefix, page.getCommonPrefixes());
                appendFiles(entries, prefix, page.getObjectSummaries());
                continuationToken = page.isTruncated() ? page.getNextContinuationToken() : null;
                if (page.isTruncated() && StringUtils.isBlank(continuationToken)) {
                    throw new IllegalStateException("S3 listing returned a truncated page without a continuation token");
                }
            } while (continuationToken != null);
            return new ArrayList<>(entries.values());
        } finally {
            client.shutdown();
        }
    }

    private void appendDirectories(
            Map<String, FileEntryVO> entries,
            String prefix,
            List<String> commonPrefixes) {
        if (commonPrefixes == null) {
            return;
        }
        for (String directoryPrefix : commonPrefixes) {
            String relative = directoryPrefix.substring(prefix.length()).replaceAll("/+$", "");
            if (relative.isBlank() || relative.contains("/")) {
                continue;
            }
            String path = ObjectStoragePathUtils.fromObjectKey(directoryPrefix);
            entries.put(path, new FileEntryVO(relative, path, "DIRECTORY", null, null));
        }
    }

    private void appendFiles(
            Map<String, FileEntryVO> entries,
            String prefix,
            List<S3ObjectSummary> summaries) {
        if (summaries == null) {
            return;
        }
        for (S3ObjectSummary summary : summaries) {
            String key = summary.getKey();
            if (key == null || key.equals(prefix) || !key.startsWith(prefix)) {
                continue;
            }
            String name = key.substring(prefix.length());
            if (name.isBlank() || name.contains("/")) {
                continue;
            }
            String path = ObjectStoragePathUtils.fromObjectKey(key);
            Date lastModified = summary.getLastModified();
            entries.put(path, new FileEntryVO(
                    name,
                    path,
                    "FILE",
                    summary.getSize(),
                    lastModified == null ? null : lastModified.getTime()));
        }
    }

    private static AmazonS3 buildClient(ObjectStorageConnectionParam param) {
        ClientConfiguration configuration = new ClientConfiguration()
                .withConnectionTimeout(param.getConnectTimeoutMs())
                .withSocketTimeout(param.getRequestTimeoutMs())
                .withRequestTimeout(param.getRequestTimeoutMs());
        return AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(new EndpointConfiguration(param.getEndpoint(), param.getRegion()))
                .withPathStyleAccessEnabled(param.pathStyleAccess())
                .withCredentials(credentialsProvider(param))
                .withClientConfiguration(configuration)
                .build();
    }

    private static AWSCredentialsProvider credentialsProvider(ObjectStorageConnectionParam param) {
        if (param.credentialMode() == ObjectStorageCredentialMode.INSTANCE_PROFILE) {
            return InstanceProfileCredentialsProvider.getInstance();
        }
        return new AWSStaticCredentialsProvider(
                new BasicAWSCredentials(param.accessKey(), param.secretKey()));
    }

    private ObjectStorageConnectionParam requireParam(ConnectionParam param) {
        if (!(param instanceof ObjectStorageConnectionParam)) {
            throw new IllegalArgumentException("Invalid S3-compatible connection param type");
        }
        return (ObjectStorageConnectionParam) param;
    }
}
