"""Dispatch the existing custom metadata source by sourcePythonClass."""

import importlib
from typing import Optional

from metadata.generated.schema.metadataIngestion.workflow import Source as WorkflowSource
from metadata.ingestion.api.steps import Source
from metadata.ingestion.api.steps import InvalidSourceException
from metadata.ingestion.ometa.ometa_api import OpenMetadata


class CustomDatabaseSource(Source):
    """Adapter used by the 1.12.10 source loader for custom database services."""

    @classmethod
    def create(
        cls,
        config_dict: dict,
        metadata: OpenMetadata,
        pipeline_name: Optional[str] = None,
    ):
        config = WorkflowSource.model_validate(config_dict)
        connection = config.serviceConnection.root.config
        source_python_class = connection.sourcePythonClass
        if not source_python_class:
            raise InvalidSourceException(
                "CustomDatabase connection requires sourcePythonClass"
            )

        module_name, separator, class_name = source_python_class.rpartition(".")
        if not separator:
            raise InvalidSourceException(
                f"Invalid custom source class: {source_python_class}"
            )
        source_class = getattr(importlib.import_module(module_name), class_name)
        return source_class.create(config_dict, metadata, pipeline_name)
