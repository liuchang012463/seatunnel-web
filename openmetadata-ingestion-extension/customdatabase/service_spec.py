"""OpenMetadata 1.12.10 ServiceSpec for custom JDBC database profilers."""

from metadata.utils.service_spec.default import DefaultDatabaseSpec

from .connection import CustomDatabaseConnectionAdapter
from .source import CustomDatabaseSource


ServiceSpec = DefaultDatabaseSpec(
    metadata_source_class=CustomDatabaseSource,
    connection_class=CustomDatabaseConnectionAdapter,
)
