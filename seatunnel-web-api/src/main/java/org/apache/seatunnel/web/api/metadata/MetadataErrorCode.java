package org.apache.seatunnel.web.api.metadata;

/** Error categories persisted in the local metadata binding, never raw OM responses. */
public enum MetadataErrorCode {
    OM_CONNECTION_ERROR,
    OM_SERVICE_SYNC_ERROR,
    OM_PIPELINE_DEPLOY_ERROR,
    OM_PIPELINE_TRIGGER_ERROR,
    OM_PIPELINE_STATUS_ERROR,
    SOURCE_CONNECTION_ERROR,
    PIPELINE_EXECUTION_ERROR,
    CONNECTOR_NOT_SUPPORTED,
    PROFILE_NOT_AVAILABLE,
    METADATA_EXTENSION_NOT_CONFIGURED,
    METADATA_EXTENSION_ERROR
}
