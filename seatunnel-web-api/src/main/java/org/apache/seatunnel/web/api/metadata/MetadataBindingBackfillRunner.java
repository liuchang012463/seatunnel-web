package org.apache.seatunnel.web.api.metadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.service.MetadataBindingCommandService;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Creates the local control-plane row for legacy data sources that pre-date
 * the metadata binding migration. The operation is idempotent and deliberately
 * does not call OpenMetadata; the normal reconciler owns all external work.
 */
@Slf4j
@Component
public class MetadataBindingBackfillRunner {

    private final DataSourceDao dataSourceDao;
    private final MetadataBindingDao metadataBindingDao;
    private final MetadataBindingCommandService metadataBindingCommandService;

    public MetadataBindingBackfillRunner(
            DataSourceDao dataSourceDao,
            MetadataBindingDao metadataBindingDao,
            MetadataBindingCommandService metadataBindingCommandService) {
        this.dataSourceDao = dataSourceDao;
        this.metadataBindingDao = metadataBindingDao;
        this.metadataBindingCommandService = metadataBindingCommandService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillLegacyBindings() {
        int created = 0;
        for (DataSource dataSource : dataSourceDao.queryAll()) {
            if (dataSource == null || dataSource.getId() == null) {
                continue;
            }
            try {
                if (metadataBindingDao.queryByDataSourceId(dataSource.getId()) == null) {
                    metadataBindingCommandService.createForDataSource(dataSource.getId());
                    created++;
                }
            } catch (DuplicateKeyException ignored) {
                // Another application instance won the idempotent insert race.
            } catch (Exception e) {
                // One malformed historical row must not prevent the application from starting.
                log.warn("Metadata binding backfill skipped dataSourceId={}, type={}",
                        dataSource.getId(), e.getClass().getSimpleName());
            }
        }
        if (created > 0) {
            log.info("Metadata binding backfill created {} local binding(s)", created);
        }
    }
}
