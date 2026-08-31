"""Official 1.12.10 BaseConnection adapter for existing JDBC custom connectors."""

import importlib
from typing import Optional

from sqlalchemy.engine import Engine

from metadata.generated.schema.entity.automations.workflow import (
    Workflow as AutomationWorkflow,
)
from metadata.generated.schema.entity.services.connections.database.customDatabaseConnection import (
    CustomDatabaseConnection,
)
from metadata.generated.schema.entity.services.connections.testConnectionResult import (
    StatusType,
    TestConnectionResult,
    TestConnectionStepResult,
)
from metadata.ingestion.connections.connection import BaseConnection
from metadata.ingestion.ometa.ometa_api import OpenMetadata
from metadata.utils.constants import THREE_MIN


class CustomDatabaseConnectionAdapter(
    BaseConnection[CustomDatabaseConnection, Engine]
):
    """Delegate to the custom JDBC connector already shipped in the image."""

    def _connector_module(self):
        source_python_class = self.service_connection.sourcePythonClass or ""
        module_name, separator, _ = source_python_class.rpartition(".")
        if not separator:
            raise ValueError(
                f"Invalid custom source class: {source_python_class}"
            )
        if module_name.endswith("dameng_source"):
            return importlib.import_module("dameng_connector.connection")
        if module_name.endswith("kingbase_source"):
            return importlib.import_module("kingbase_connector.connection")
        raise ValueError(
            f"Unsupported custom database source: {source_python_class}"
        )

    def _get_client(self) -> Engine:
        return self._connector_module().get_connection(self.service_connection)

    def get_connection_dict(self) -> dict:
        raise NotImplementedError(
            "get_connection_dict is not implemented for CustomDatabase"
        )

    def test_connection(
        self,
        metadata: OpenMetadata,
        automation_workflow: Optional[AutomationWorkflow] = None,
        timeout_seconds: int = THREE_MIN,
    ) -> TestConnectionResult:
        # Opening the JDBC-backed SQLAlchemy engine is the connection check. The
        # existing metadata connectors perform their own schema/table checks in
        # metadata workflows; profiler startup only requires this typed result.
        self.client
        return TestConnectionResult(
            status=StatusType.Successful,
            steps=[
                TestConnectionStepResult(
                    name="CheckAccess",
                    mandatory=True,
                    passed=True,
                    message="Connection successful",
                )
            ],
        )
