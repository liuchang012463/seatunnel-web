package org.apache.seatunnel.web.api.metadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorAdapter;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorRegistry;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataResource;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataResourceDetail;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataResourceField;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.common.utils.MetadataStableName;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceResourceDetailVO;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceResourceFieldVO;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceResourcePageVO;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceResourceVO;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only exploration of the OpenMetadata assets of a non-relational data source:
 * Kafka topics, object-storage containers, drive directories/files, API collections and
 * endpoints, and search indexes.
 *
 * <p>These assets have no OpenMetadata profiler. What can be shown is the extracted
 * schema plus, when the ingestion pipeline has been allowed to collect it, the sample
 * payload.</p>
 */
@Slf4j
@Service
public class NonRelationalExplorationService {

    private static final int DEFAULT_PAGE_SIZE = 200;
    private static final int MAX_PAGE_SIZE = 1000;

    private final OpenMetadataConfigResolver configResolver;
    private final DataSourceDao dataSourceDao;
    private final MetadataBindingDao metadataBindingDao;
    private final MetadataConnectorRegistry connectorRegistry;
    private final OpenMetadataClient openMetadataClient;

    public NonRelationalExplorationService(
            OpenMetadataConfigResolver configResolver,
            DataSourceDao dataSourceDao,
            MetadataBindingDao metadataBindingDao,
            MetadataConnectorRegistry connectorRegistry,
            OpenMetadataClient openMetadataClient) {
        this.configResolver = configResolver;
        this.dataSourceDao = dataSourceDao;
        this.metadataBindingDao = metadataBindingDao;
        this.connectorRegistry = connectorRegistry;
        this.openMetadataClient = openMetadataClient;
    }

    /** Lists the assets of one data source, optionally restricted to a single entity type. */
    public DataSourceResourcePageVO listResources(
            Long dataSourceId, String resourceType, Integer limit) {
        ResourceContext context = context(dataSourceId, resourceType);
        int pageSize = limit == null || limit <= 0
                ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        List<DataSourceResourceVO> resources = new ArrayList<>();
        List<String> entityTypes = new ArrayList<>();
        boolean truncated = false;
        for (OmResourceType type : context.types()) {
            entityTypes.add(type.entityType());
            OpenMetadataPage<OpenMetadataResource> page = openMetadataClient.listResourcesPage(
                    type, context.serviceFqn(), pageSize, null);
            for (OpenMetadataResource resource : page.data()) {
                resources.add(toVo(type, resource));
            }
            if (page.total() > page.data().size()) {
                truncated = true;
            }
        }
        DataSourceResourcePageVO result = new DataSourceResourcePageVO();
        result.setResources(resources);
        result.setEntityTypes(entityTypes);
        result.setTruncated(truncated);
        return result;
    }

    /** Reads one asset with its schema and, when collected, its sample payload. */
    public DataSourceResourceDetailVO getResourceDetail(
            Long dataSourceId, String resourceType, String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            throw invalid("resourceId");
        }
        ResourceContext context = context(dataSourceId, resourceType);
        OmResourceType type = context.types().get(0);
        OpenMetadataResourceDetail detail = openMetadataClient.getResourceDetail(type, resourceId);
        if (detail == null || detail.resource() == null) {
            throw invalid("resource does not exist in OpenMetadata");
        }
        String resourceServiceFqn = detail.resource().serviceFullyQualifiedName();
        if (resourceServiceFqn != null
                && !resourceServiceFqn.isBlank()
                && !context.serviceFqn().equals(resourceServiceFqn)) {
            throw invalid("resource does not belong to this data source");
        }
        return toVo(type, detail);
    }

    private ResourceContext context(Long dataSourceId, String resourceType) {
        if (!configResolver.isEnabled()) {
            throw invalid("OpenMetadata integration is disabled");
        }
        if (dataSourceId == null || dataSourceId <= 0) {
            throw invalid("dataSourceId");
        }
        DataSource source = dataSourceDao.queryById(dataSourceId);
        if (source == null || source.getStatus() == DataSourceLifecycleStatus.REVOKED) {
            throw invalid("data source is unavailable");
        }
        MetadataConnectorAdapter adapter = connectorRegistry.find(source.getDbType())
                .orElseThrow(() -> new MetadataIntegrationException(
                        MetadataErrorCode.CONNECTOR_NOT_SUPPORTED,
                        "OpenMetadata connector is not enabled for this data source type"));
        List<OmResourceType> supported = adapter.resourceTypes();
        if (supported.isEmpty()) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.CONNECTOR_NOT_SUPPORTED,
                    "OpenMetadata non-relational exploration is not supported for this data source type");
        }
        List<OmResourceType> selected = new ArrayList<>();
        if (resourceType == null || resourceType.isBlank()) {
            selected.addAll(supported);
        } else {
            OmResourceType requested = OmResourceType.fromEntityType(resourceType);
            if (requested == null || !supported.contains(requested)) {
                throw invalid("resourceType is not supported by this data source");
            }
            selected.add(requested);
        }
        MetadataSourceBinding binding = metadataBindingDao.queryByDataSourceId(dataSourceId);
        if (binding == null
                || binding.getDesiredState() != MetadataDesiredState.ACTIVE
                || binding.getSyncStatus() != MetadataSyncStatus.READY) {
            throw invalid("metadata synchronization is not ready");
        }
        String serviceFqn = binding.getOmServiceFqn();
        if (serviceFqn == null || serviceFqn.isBlank()) {
            serviceFqn = MetadataStableName.serviceFqn(dataSourceId);
        }
        openMetadataClient.assertFixedVersion();
        return new ResourceContext(selected, serviceFqn);
    }

    private static DataSourceResourceVO toVo(OmResourceType type, OpenMetadataResource resource) {
        DataSourceResourceVO vo = new DataSourceResourceVO();
        vo.setId(resource.id());
        vo.setName(resource.name());
        vo.setFullyQualifiedName(resource.fullyQualifiedName());
        vo.setEntityType(type.entityType());
        vo.setEntityLabel(type.label());
        vo.setDescription(resource.description());
        vo.setFieldCount(resource.fieldCount());
        vo.setChildCount(resource.childCount());
        vo.setTags(resource.tags());
        return vo;
    }

    private static DataSourceResourceDetailVO toVo(
            OmResourceType type, OpenMetadataResourceDetail detail) {
        DataSourceResourceDetailVO vo = new DataSourceResourceDetailVO();
        vo.setResource(toVo(type, detail.resource()));
        List<DataSourceResourceFieldVO> fields = new ArrayList<>();
        for (OpenMetadataResourceField field : detail.fields()) {
            DataSourceResourceFieldVO fieldVo = new DataSourceResourceFieldVO();
            fieldVo.setName(field.name());
            fieldVo.setDataType(field.dataType());
            fieldVo.setDescription(field.description());
            fieldVo.setTags(field.tags());
            fields.add(fieldVo);
        }
        vo.setFields(fields);
        vo.setRequestFields(toFieldVos(detail.requestFields()));
        vo.setResponseFields(toFieldVos(detail.responseFields()));
        vo.setSampleDataAvailable(detail.sampleDataAvailable());
        vo.setSampleColumns(detail.sampleColumns());
        vo.setSampleRows(detail.sampleRows());
        vo.setMessages(detail.messages());
        return vo;
    }

    private static List<DataSourceResourceFieldVO> toFieldVos(List<OpenMetadataResourceField> source) {
        List<DataSourceResourceFieldVO> fields = new ArrayList<>();
        for (OpenMetadataResourceField field : source) {
            DataSourceResourceFieldVO fieldVo = new DataSourceResourceFieldVO();
            fieldVo.setName(field.name());
            fieldVo.setDataType(field.dataType());
            fieldVo.setDescription(field.description());
            fieldVo.setTags(field.tags());
            fields.add(fieldVo);
        }
        return fields;
    }

    private static ServiceException invalid(String reason) {
        return new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, reason);
    }

    private record ResourceContext(List<OmResourceType> types, String serviceFqn) {
    }
}
