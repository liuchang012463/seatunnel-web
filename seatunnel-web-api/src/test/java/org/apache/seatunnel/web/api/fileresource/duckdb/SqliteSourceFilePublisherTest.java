package org.apache.seatunnel.web.api.fileresource.duckdb;

import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValueFactory;
import org.apache.seatunnel.web.api.fileresource.storage.FileResourceStorageProvider;
import org.apache.seatunnel.web.api.fileresource.storage.StorageObjectMetadata;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.OutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqliteSourceFilePublisherTest {

    private static final String OBJECT_KEY = "seatunnel-web-resource/native_engine.db";
    private static final String INIT_SQL = "SET autoinstall_known_extensions = false;\n"
            + "LOAD sqlite;\n"
            + "ATTACH IF NOT EXISTS '" + SqliteSourceFilePublisher.ATTACH_PATH_MARKER
            + "' AS sqlite_source (TYPE sqlite, READ_ONLY);";

    @Test
    void uploadsTheObjectAndRewritesTheSqliteAttachPath() throws Exception {
        byte[] contents = new byte[] {1, 2, 3, 4};
        FileResourceStorageProvider storageProvider = mock(FileResourceStorageProvider.class);
        SeaTunnelRestClient restClient = mock(SeaTunnelRestClient.class);
        when(storageProvider.head(OBJECT_KEY)).thenReturn(new StorageObjectMetadata(4L, null, null));
        doAnswer(invocation -> {
            OutputStream output = invocation.getArgument(1);
            output.write(contents);
            return null;
        }).when(storageProvider).download(eq(OBJECT_KEY), any(OutputStream.class));
        when(restClient.uploadDriverJar(eq(4L), any(byte[].class), anyString()))
                .thenReturn("/opt/seatunnel/driver-jars/abc-sqlite-source.db.jar");

        SqliteSourceFilePublisher publisher = new SqliteSourceFilePublisher(storageProvider, restClient);
        String published = publisher.publish(4L, config());
        ConfigObject properties = sqliteProperties(published);
        String initSql = String.valueOf(properties.get(SqliteSourceFilePublisher.SESSION_INIT_SQL_PROPERTY)
                .unwrapped());
        ArgumentCaptor<String> uploadedFileName = ArgumentCaptor.forClass(String.class);

        assertNull(properties.get(SqliteSourceFilePublisher.OBJECT_KEY_PROPERTY));
        assertTrue(initSql.contains("'/opt/seatunnel/driver-jars/abc-sqlite-source.db.jar'"));
        assertFalse(initSql.contains(SqliteSourceFilePublisher.ATTACH_PATH_MARKER));
        verify(storageProvider).download(eq(OBJECT_KEY), any(OutputStream.class));
        verify(restClient).uploadDriverJar(eq(4L), eq(contents), uploadedFileName.capture());
        assertTrue(uploadedFileName.getValue().endsWith(".db.jar"));
    }

    @Test
    void rejectsObjectsThatExceedTheEngineUploadLimitBeforeDownloading() throws Exception {
        FileResourceStorageProvider storageProvider = mock(FileResourceStorageProvider.class);
        SeaTunnelRestClient restClient = mock(SeaTunnelRestClient.class);
        long tooLarge = 64L * 1024 * 1024 + 1;
        when(storageProvider.head(OBJECT_KEY)).thenReturn(new StorageObjectMetadata(tooLarge, null, null));

        SqliteSourceFilePublisher publisher = new SqliteSourceFilePublisher(storageProvider, restClient);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> publisher.publish(4L, config()));

        assertTrue(error.getMessage().contains(String.valueOf(tooLarge)));
        verify(storageProvider, never()).download(anyString(), any(OutputStream.class));
        verify(restClient, never()).uploadDriverJar(any(), any(), anyString());
    }

    @Test
    void leavesConfigsWithoutSqliteStagingMetadataUntouched() throws Exception {
        FileResourceStorageProvider storageProvider = mock(FileResourceStorageProvider.class);
        SeaTunnelRestClient restClient = mock(SeaTunnelRestClient.class);
        SqliteSourceFilePublisher publisher = new SqliteSourceFilePublisher(storageProvider, restClient);
        String config = "source { Jdbc { url = \"jdbc:postgresql://example/db\" } }";

        assertEquals(config, publisher.publish(4L, config));
        verify(storageProvider, never()).head(anyString());
        verify(restClient, never()).uploadDriverJar(any(), any(), anyString());
    }

    private static ConfigObject sqliteProperties(String hocon) {
        ConfigObject root = ConfigFactory.parseString(hocon).root();
        ConfigObject source = (ConfigObject) root.get("source");
        ConfigObject jdbc = (ConfigObject) source.get("Jdbc");
        return (ConfigObject) jdbc.get("properties");
    }

    private static String config() {
        ConfigObject properties = ConfigValueFactory.fromMap(Map.of(
                SqliteSourceFilePublisher.SESSION_INIT_SQL_PROPERTY, INIT_SQL,
                SqliteSourceFilePublisher.OBJECT_KEY_PROPERTY, OBJECT_KEY));
        ConfigObject jdbc = ConfigValueFactory.fromMap(Map.of(
                "url", "jdbc:duckdb:",
                "driver", "org.duckdb.DuckDBDriver",
                "properties", properties));
        ConfigObject source = ConfigValueFactory.fromMap(Map.of("Jdbc", jdbc));
        return ConfigValueFactory.fromMap(Map.of("source", source))
                .render(com.typesafe.config.ConfigRenderOptions.defaults().setJson(false));
    }
}
