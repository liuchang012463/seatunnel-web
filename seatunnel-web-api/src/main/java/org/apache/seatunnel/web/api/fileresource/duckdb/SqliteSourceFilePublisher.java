package org.apache.seatunnel.web.api.fileresource.duckdb;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.fileresource.storage.FileResourceStorageProvider;
import org.apache.seatunnel.web.api.fileresource.storage.StorageObjectMetadata;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/** Stages a SQLite file on the Engine cluster before a DuckDB JDBC source starts. */
@Slf4j
@Component
public class SqliteSourceFilePublisher {

    public static final String OBJECT_KEY_PROPERTY = "duckdb.sqlite_source_object_key";
    public static final String SESSION_INIT_SQL_PROPERTY = "duckdb.session_init_sql";
    public static final String ATTACH_PATH_MARKER = "__SEATUNNEL_WEB_SQLITE_SOURCE_PATH__";

    /** The Engine driver artifact endpoint accepts at most 64 MiB per upload. */
    private static final long MAX_ENGINE_UPLOAD_BYTES = 64L * 1024 * 1024;

    private final FileResourceStorageProvider storageProvider;
    private final SeaTunnelRestClient restClient;

    public SqliteSourceFilePublisher(
            FileResourceStorageProvider storageProvider,
            SeaTunnelRestClient restClient) {
        this.storageProvider = storageProvider;
        this.restClient = restClient;
    }

    /** Uploads the marked file and replaces the placeholder with its cluster-local path. */
    public String publish(Long clientId, String hoconConfig) throws IOException {
        if (hoconConfig == null || hoconConfig.isBlank()) {
            return hoconConfig;
        }

        Config config = ConfigFactory.parseString(hoconConfig);
        ConfigValue sourceValue = config.root().get("source");
        if (!(sourceValue instanceof ConfigObject source)) {
            return hoconConfig;
        }

        for (Map.Entry<String, ConfigValue> sourceEntry : source.entrySet()) {
            if (!(sourceEntry.getValue() instanceof ConfigObject connector)) {
                continue;
            }
            ConfigValue propertiesValue = connector.get("properties");
            if (!(propertiesValue instanceof ConfigObject properties)) {
                continue;
            }
            ConfigValue objectKeyValue = properties.get(OBJECT_KEY_PROPERTY);
            if (objectKeyValue == null) {
                continue;
            }

            String objectKey = String.valueOf(objectKeyValue.unwrapped()).trim();
            ConfigValue initSqlValue = properties.get(SESSION_INIT_SQL_PROPERTY);
            if (objectKey.isBlank() || initSqlValue == null) {
                throw new IllegalArgumentException("SQLite source staging metadata is incomplete");
            }
            String initSql = String.valueOf(initSqlValue.unwrapped());
            String markerLiteral = sqlLiteral(ATTACH_PATH_MARKER);
            if (!initSql.contains(markerLiteral)) {
                throw new IllegalArgumentException("SQLite source attach path marker is missing");
            }

            Path temporaryFile = Files.createTempFile("seatunnel-web-sqlite-source-", suffix(objectKey));
            try {
                StorageObjectMetadata metadata = storageProvider.head(objectKey);
                if (metadata != null && metadata.size() != null) {
                    requireUploadSize(metadata.size());
                }
                try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                    storageProvider.download(objectKey, output);
                }

                long size = Files.size(temporaryFile);
                requireUploadSize(size);
                if (size == 0) {
                    throw new IllegalArgumentException("SQLite source file is empty");
                }

                // SeaTunnel 3.0's cluster file endpoint is the driver-JAR servlet and requires a
                // .jar filename. Keep the SQLite suffix for clarity; DuckDB opens these bytes by
                // the explicit path below, and the file is never configured as a JDBC driver.
                String fileName = "sqlite-" + sha256(temporaryFile) + suffix(objectKey) + ".jar";
                String enginePath = restClient.uploadDriverJar(
                        clientId, Files.readAllBytes(temporaryFile), fileName);
                String rewrittenSql = initSql.replace(markerLiteral, sqlLiteral(enginePath));
                ConfigObject updatedProperties = properties
                        .withValue(SESSION_INIT_SQL_PROPERTY, ConfigValueFactory.fromAnyRef(rewrittenSql))
                        .withoutKey(OBJECT_KEY_PROPERTY);
                ConfigObject updatedConnector = connector.withValue("properties", updatedProperties);
                ConfigObject updatedSource = source.withValue(sourceEntry.getKey(), updatedConnector);
                Config updatedConfig = config.withValue("source", updatedSource);

                log.info("Staged SQLite source on Engine clientId={}, objectKey={}, sizeBytes={}, enginePath={}",
                        clientId, objectKey, size, enginePath);
                return updatedConfig.root().render(ConfigRenderOptions.defaults().setJson(false));
            } finally {
                Files.deleteIfExists(temporaryFile);
            }
        }
        return hoconConfig;
    }

    private void requireUploadSize(long size) {
        if (size > MAX_ENGINE_UPLOAD_BYTES) {
            throw new IllegalArgumentException("SQLite source file is " + size
                    + " bytes, exceeding the Engine file upload limit of " + MAX_ENGINE_UPLOAD_BYTES + " bytes");
        }
    }

    private String suffix(String objectKey) {
        String key = objectKey.toLowerCase(Locale.ROOT);
        if (key.endsWith(".sqlite3")) {
            return ".sqlite3";
        }
        return key.endsWith(".sqlite") ? ".sqlite" : ".db";
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String sqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
