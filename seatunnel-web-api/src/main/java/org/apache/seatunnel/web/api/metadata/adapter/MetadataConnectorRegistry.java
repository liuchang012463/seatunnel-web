package org.apache.seatunnel.web.api.metadata.adapter;

import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

@Component
public class MetadataConnectorRegistry {

    private final Map<DbType, MetadataConnectorAdapter> adapters = new EnumMap<>(DbType.class);

    public MetadataConnectorRegistry(Collection<MetadataConnectorAdapter> adapters) {
        for (MetadataConnectorAdapter adapter : adapters) {
            this.adapters.put(adapter.dataSourceType(), adapter);
        }
    }

    public Optional<MetadataConnectorAdapter> find(DbType dbType) {
        return Optional.ofNullable(adapters.get(dbType));
    }

    public boolean supports(DbType dbType) {
        return adapters.containsKey(dbType);
    }

    public MetadataConnectorAdapter require(DbType dbType) {
        return find(dbType).orElseThrow(() -> new MetadataIntegrationException(
                MetadataErrorCode.CONNECTOR_NOT_SUPPORTED,
                "OpenMetadata 1.12.10 connector is not enabled for this data source type"));
    }
}
