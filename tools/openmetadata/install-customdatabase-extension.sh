#!/usr/bin/env bash
set -euo pipefail

# Install the versioned CustomDatabase package into the host directory already
# mounted by the OpenMetadata ingestion container. This deliberately does not
# rebuild or restart any container.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="${SCRIPT_DIR}/ingestion-extension/customdatabase-om-1.12.10.0"
TARGET_DIR="${OPENMETADATA_EXTENSION_DIR:-/data/vol_a/lc/open_metadata/extensions/customdatabase-om-1.12.10.0}"
DEFAULT_VASTBASE_DRIVER="/mnt/djc/vastbase/Vastbase-G100-2.16_pg_2026062910.jar"
VASTBASE_JDBC_DRIVER_SOURCE="${VASTBASE_JDBC_DRIVER_SOURCE:-${DEFAULT_VASTBASE_DRIVER}}"

[[ -d "${SOURCE_DIR}" ]] || {
  echo "error: extension source directory does not exist: ${SOURCE_DIR}" >&2
  exit 1
}

mkdir -p "${TARGET_DIR}"
cp -a "${SOURCE_DIR}/." "${TARGET_DIR}/"

if [[ -f "${VASTBASE_JDBC_DRIVER_SOURCE}" ]]; then
  driver_dir="${TARGET_DIR}/vastbase_connector/drivers"
  mkdir -p "${driver_dir}"
  install -m 0644 "${VASTBASE_JDBC_DRIVER_SOURCE}" \
    "${driver_dir}/$(basename "${VASTBASE_JDBC_DRIVER_SOURCE}")"
  echo "installed Vastbase JDBC driver: ${driver_dir}/$(basename "${VASTBASE_JDBC_DRIVER_SOURCE}")"
else
  echo "warning: Vastbase JDBC driver not found: ${VASTBASE_JDBC_DRIVER_SOURCE}" >&2
  echo "         provide VASTBASE_JDBC_DRIVER_SOURCE before running an OM pipeline" >&2
fi

echo "installed OpenMetadata CustomDatabase extension: ${TARGET_DIR}"
echo "restart/rebuild is not required when this directory is bind-mounted"
