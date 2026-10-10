package org.apache.seatunnel.web.api.metadata.adapter;

import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

/** Uses the mounted CustomDatabase Vastbase connector in the 2.0.4 extension package. */
@Component
public class VastbaseMetadataConnectorAdapter extends CustomDatabaseMetadataConnectorAdapter {

    @Override
    public DbType dataSourceType() {
        return DbType.VASTBASE;
    }

    @Override
    protected String sourcePythonClass() {
        return "metadata.ingestion.source.database.customdatabase.vastbase_connector.vastbase_source.VastbaseSource";
    }

    @Override
    protected boolean useDatasourceSchemaFilter() {
        // Existing Vastbase sources may be referenced by SeaTunnel jobs that
        // rely on their saved schemaName. Keep that setting for jobs, while
        // allowing OpenMetadata to discover all schemas the account can see.
        return false;
    }
}
