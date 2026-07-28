package org.apache.seatunnel.plugin.datasource.s3.client;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import org.apache.seatunnel.plugin.datasource.s3.param.MinioConnectionParam;
import org.apache.seatunnel.web.spi.bean.vo.FileEntryVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3ObjectStorageClientTest {

    @Test
    void listsOneLevelAcrossPages() {
        AmazonS3 amazonS3 = mock(AmazonS3.class);
        ListObjectsV2Result first = new ListObjectsV2Result();
        first.setCommonPrefixes(List.of("root/sub/"));
        first.getObjectSummaries().add(summary("root/a.bin", 10L));
        first.setTruncated(true);
        first.setNextContinuationToken("next");
        ListObjectsV2Result second = new ListObjectsV2Result();
        second.getObjectSummaries().add(summary("root/b.bin", 20L));
        second.setTruncated(false);
        when(amazonS3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(first, second);

        S3ObjectStorageClient client = new S3ObjectStorageClient(param -> amazonS3);
        List<FileEntryVO> entries = client.listEntries(param(), "/root");

        assertEquals(3, entries.size());
        assertEquals("/root/sub", entries.get(0).getPath());
        assertEquals("/root/a.bin", entries.get(1).getPath());
        assertEquals("/root/b.bin", entries.get(2).getPath());
        verify(amazonS3, times(2)).listObjectsV2(any(ListObjectsV2Request.class));
        verify(amazonS3).shutdown();
    }

    @Test
    void connectivityUsesConfiguredRootPrefix() {
        AmazonS3 amazonS3 = mock(AmazonS3.class);
        when(amazonS3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(new ListObjectsV2Result());
        S3ObjectStorageClient client = new S3ObjectStorageClient(param -> amazonS3);

        client.checkDataSourceConnectivity(param());

        ArgumentCaptor<ListObjectsV2Request> captor =
                ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(amazonS3).listObjectsV2(captor.capture());
        assertEquals("root/", captor.getValue().getPrefix());
        assertEquals(1, captor.getValue().getMaxKeys());
        verify(amazonS3).shutdown();
    }

    @Test
    void mapsVirtualRootAndEmptyPrefix() {
        AmazonS3 amazonS3 = mock(AmazonS3.class);
        when(amazonS3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(new ListObjectsV2Result());
        S3ObjectStorageClient client = new S3ObjectStorageClient(param -> amazonS3);

        List<FileEntryVO> entries = client.listEntries(param(), "/");

        assertTrue(entries.isEmpty());
        ArgumentCaptor<ListObjectsV2Request> captor =
                ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(amazonS3).listObjectsV2(captor.capture());
        assertEquals("root/", captor.getValue().getPrefix());
        assertEquals("/", captor.getValue().getDelimiter());
    }

    @Test
    void propagatesAuthenticationOrPermissionFailureAndClosesClient() {
        AmazonS3 amazonS3 = mock(AmazonS3.class);
        AmazonS3Exception accessDenied = new AmazonS3Exception("Access Denied");
        accessDenied.setStatusCode(403);
        when(amazonS3.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(accessDenied);
        S3ObjectStorageClient client = new S3ObjectStorageClient(param -> amazonS3);

        AmazonS3Exception thrown =
                assertThrows(AmazonS3Exception.class, () -> client.listEntries(param(), "/root"));

        assertEquals(403, thrown.getStatusCode());
        verify(amazonS3).shutdown();
    }

    @Test
    void rejectsBrowseOutsideRootBeforeOpeningClient() {
        S3ObjectStorageClient client = new S3ObjectStorageClient(param -> {
            throw new AssertionError("client must not be created");
        });
        assertThrows(IllegalArgumentException.class,
                () -> client.listEntries(param(), "/outside"));
    }

    private MinioConnectionParam param() {
        MinioConnectionParam param = new MinioConnectionParam();
        param.setEndpoint("http://minio:9000");
        param.setBucket("archive");
        param.setBasePath("/root");
        param.setAccessKey("minio");
        param.setSecretKey("minio-secret");
        return param;
    }

    private S3ObjectSummary summary(String key, long size) {
        S3ObjectSummary summary = new S3ObjectSummary();
        summary.setKey(key);
        summary.setSize(size);
        summary.setLastModified(new Date(1000L));
        return summary;
    }
}
