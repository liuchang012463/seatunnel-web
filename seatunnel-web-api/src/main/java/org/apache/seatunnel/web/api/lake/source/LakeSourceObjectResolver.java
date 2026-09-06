package org.apache.seatunnel.web.api.lake.source;

import lombok.NonNull;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.api.metadata.OpenMetadataProperties;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTable;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.common.utils.MetadataStableName;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves a source table by OM UUID and verifies that it belongs to the
 * selected SeaTunnel data source before constructing a source baseline.
 */
@Component
public class LakeSourceObjectResolver {

    private final DataSourceDao dataSourceDao;
    private final MetadataBindingDao metadataBindingDao;
    private final OpenMetadataClient openMetadataClient;
    private final OpenMetadataProperties openMetadataProperties;

    @Autowired
    public LakeSourceObjectResolver(
            @NonNull DataSourceDao dataSourceDao,
            @NonNull MetadataBindingDao metadataBindingDao,
            @NonNull OpenMetadataClient openMetadataClient,
            @NonNull OpenMetadataProperties openMetadataProperties) {
        this.dataSourceDao = dataSourceDao;
        this.metadataBindingDao = metadataBindingDao;
        this.openMetadataClient = openMetadataClient;
        this.openMetadataProperties = openMetadataProperties;
    }

    /**
     * Test-friendly constructor for callers that already resolved the source
     * service. It still performs UUID and ownership checks in {@link #resolve}.
     */
    public LakeSourceObjectResolver(
            @NonNull OpenMetadataClient openMetadataClient,
            @NonNull OpenMetadataProperties openMetadataProperties) {
        this.dataSourceDao = null;
        this.metadataBindingDao = null;
        this.openMetadataClient = openMetadataClient;
        this.openMetadataProperties = openMetadataProperties;
    }

    /** Reads a fresh source table and produces a stable structural baseline. */
    public SourceObjectSnapshot resolve(Long sourceDataSourceId, String omEntityId) {
        validateId(sourceDataSourceId, "sourceDataSourceId");
        if (omEntityId == null || omEntityId.isBlank()) {
            throw missing("OpenMetadata entity UUID is required");
        }
        String expectedService = expectedService(sourceDataSourceId);
        return resolve(sourceDataSourceId, omEntityId, expectedService);
    }

    /** Resolves against an already-known OM service FQN. */
    public SourceObjectSnapshot resolve(
            Long sourceDataSourceId, String omEntityId, String expectedServiceFqn) {
        validateId(sourceDataSourceId, "sourceDataSourceId");
        if (omEntityId == null || omEntityId.isBlank()) {
            throw missing("OpenMetadata entity UUID is required");
        }
        if (expectedServiceFqn == null || expectedServiceFqn.isBlank()) {
            throw unknown("OpenMetadata ownership service is unavailable");
        }
        if (openMetadataProperties != null && !openMetadataProperties.isEnabled()) {
            throw unknown("OpenMetadata integration is disabled");
        }
        if (openMetadataProperties != null) {
            // Version verification is deliberately done immediately before a
            // user-triggered source read, matching the exploration boundary.
            try {
                openMetadataClient.assertFixedVersion();
            } catch (MetadataIntegrationException exception) {
                throw unknown("OpenMetadata version or connectivity check failed");
            } catch (RuntimeException exception) {
                throw unknown("OpenMetadata version or connectivity check failed");
            }
        }

        OpenMetadataTable table;
        try {
            table = openMetadataClient.getTable(omEntityId.trim());
        } catch (MetadataIntegrationException exception) {
            throw unknown("OpenMetadata table lookup is unavailable");
        } catch (RuntimeException exception) {
            throw unknown("OpenMetadata table lookup is unavailable");
        }
        if (table == null) {
            throw missing("OpenMetadata table does not exist");
        }
        if (!omEntityId.trim().equals(table.getId())) {
            throw missing("OpenMetadata UUID does not identify the requested table");
        }
        String actualService = table.getServiceFullyQualifiedName();
        if (actualService == null || actualService.isBlank()) {
            actualService = firstPart(table.getFullyQualifiedName());
        }
        if (actualService == null || !expectedServiceFqn.trim().equals(actualService.trim())) {
            throw missing("OpenMetadata table does not belong to this data source");
        }
        if (table.getFullyQualifiedName() == null || table.getFullyQualifiedName().isBlank()) {
            throw missing("OpenMetadata table identity is incomplete");
        }
        return SourceSchemaCanonicalizer.snapshot(table);
    }

    private String expectedService(Long sourceDataSourceId) {
        if (dataSourceDao == null || metadataBindingDao == null) {
            return MetadataStableName.serviceFqn(sourceDataSourceId);
        }
        DataSource source = dataSourceDao.queryById(sourceDataSourceId);
        if (source == null || source.getStatus() == DataSourceLifecycleStatus.REVOKED) {
            throw new ServiceException(org.apache.seatunnel.web.spi.enums.Status.DATASOURCE_NOT_EXIST);
        }
        if (!supported(source.getDbType())) {
            throw new LakeServiceException(LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN,
                    "OpenMetadata source type is not supported");
        }
        MetadataSourceBinding binding = metadataBindingDao.queryByDataSourceId(sourceDataSourceId);
        if (binding == null
                || binding.getDesiredState() != MetadataDesiredState.ACTIVE
                || binding.getSyncStatus() != MetadataSyncStatus.READY) {
            throw unknown("OpenMetadata synchronization is not ready");
        }
        String serviceFqn = binding.getOmServiceFqn();
        return serviceFqn == null || serviceFqn.isBlank()
                ? MetadataStableName.serviceFqn(sourceDataSourceId) : serviceFqn;
    }

    private static boolean supported(DbType dbType) {
        return dbType == DbType.MYSQL || dbType == DbType.POSTGRE_SQL
                || dbType == DbType.JDBC || dbType == DbType.DORIS
                || dbType == DbType.ORACLE || dbType == DbType.DAMENG
                || dbType == DbType.KINGBASE;
    }

    private static String firstPart(String value) {
        if (value == null) {
            return "";
        }
        int separator = value.indexOf('.');
        return separator < 0 ? value : value.substring(0, separator);
    }

    private static void validateId(Long id, String name) {
        if (id == null || id <= 0) {
            throw new LakeServiceException(LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN,
                    name + " must be positive");
        }
    }

    private static LakeServiceException missing(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING, message);
    }

    private static LakeServiceException unknown(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN, message);
    }
}
