#!/usr/bin/env bash
set -Eeuo pipefail

IMAGE="${OPENMETADATA_INGESTION_IMAGE:-openmetadata/ingestion:1.12.10}"
TARGET_DIR="${OPENMETADATA_EXTENSION_DIR:-/mnt/lc/open_metadata/extensions/ingestion-1.12.10}"

command -v docker >/dev/null 2>&1 || {
  echo "error: docker is required" >&2
  exit 1
}
[[ -d "$TARGET_DIR" ]] || {
  echo "error: extension directory does not exist: $TARGET_DIR" >&2
  exit 1
}
docker image inspect "$IMAGE" >/dev/null 2>&1 || {
  echo "error: runtime image was not found locally: $IMAGE" >&2
  exit 1
}

docker run --interactive --rm \
  --mount "type=bind,source=${TARGET_DIR},target=/opt/om-extensions,readonly" \
  --env OPENMETADATA_EXTENSION_ROOT=/opt/om-extensions \
  --env PYTHONPATH=/opt/om-extensions/python:/opt:/usr/python/lib/python3.10/site-packages:/home/airflow/.local/lib/python3.10/site-packages \
  --env PYTHONDONTWRITEBYTECODE=1 \
  --entrypoint /home/airflow/.local/bin/python \
  "$IMAGE" - <<'PY'
import importlib
import importlib.metadata as package_metadata
from pathlib import Path
import _jpype
import jaydebeapi
import jpype


expected_packages = {
    "openmetadata-ingestion": "1.12.10.0",
    "openmetadata-managed-apis": "1.12.10.0",
    "JayDeBeApi": "1.2.3",
    "JPype1": "1.7.1",
}
for package, expected in expected_packages.items():
    actual = package_metadata.version(package)
    assert actual == expected, (package, actual, expected)
    print(f"PASS package {package}={actual}")

assert Path(_jpype.__file__).is_file()
assert Path(jaydebeapi.__file__).is_file()
assert Path(jpype.__file__).is_file()
print(f"PASS JPype native module={_jpype.__file__}")
print(f"PASS JVM runtime={jpype.getDefaultJVMPath()}")


module_names = (
    "metadata.ingestion.source.database.customdatabase.service_spec",
    "metadata.ingestion.source.database.customdatabase.source",
    "metadata.ingestion.source.database.customdatabase.vastbase_connector.connection",
    "metadata.ingestion.source.database.customdatabase.vastbase_connector.vastbase_source",
    "dameng_connector.dameng_source",
    "kingbase_connector.kingbase_source",
)
for module_name in module_names:
    module = importlib.import_module(module_name)
    module_path = Path(module.__file__).resolve()
    if module_name.startswith("metadata.ingestion.source.database.customdatabase"):
        expected_root = Path("/opt/om-extensions/python/metadata").resolve()
    else:
        expected_root = Path("/opt/om-extensions/connectors").resolve()
    assert module_path.is_relative_to(expected_root), (module_name, module_path)
    print(f"PASS module {module_name}={module.__file__}")


from dameng_connector.connection import _find_jdbc_driver as find_dameng_driver
from kingbase_connector.connection import _find_jdbc_driver as find_kingbase_driver
from metadata.generated.schema.entity.services.serviceType import ServiceType
from metadata.ingestion.source.database.customdatabase.vastbase_connector.connection import (
    _find_jdbc_driver as find_vastbase_driver,
)
from metadata.utils.service_spec.service_spec import BaseSpec


drivers = {
    "dameng": Path(find_dameng_driver()),
    "kingbase": Path(find_kingbase_driver()),
    "vastbase": Path(find_vastbase_driver()),
}
for name, path in drivers.items():
    assert path.is_file(), (name, path)
    print(f"PASS driver {name}={path}")

spec = BaseSpec.get_for_source(ServiceType.Database, "customdatabase")
assert spec.__class__.__name__ == "DefaultDatabaseSpec"
print(f"PASS ServiceSpec {spec.__class__.__module__}.{spec.__class__.__name__}")
PY

echo "OpenMetadata ingestion extension bundle verification passed: $TARGET_DIR"
