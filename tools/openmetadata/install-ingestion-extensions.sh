#!/usr/bin/env bash
set -Eeuo pipefail

# Build a host-side extension bundle from the already imported combined image.
# The bundle is later mounted into the official 1.12.10 image, so the runtime
# image itself never needs to be rebuilt when an extension changes.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_IMAGE="${OPENMETADATA_EXTENSIONS_SOURCE_IMAGE:-openmetadata/ingestion:1.12.10-kingbase-dameng-vastbase}"
RUNTIME_IMAGE="${OPENMETADATA_INGESTION_IMAGE:-openmetadata/ingestion:1.12.10}"
TARGET_DIR="${OPENMETADATA_EXTENSION_DIR:-/mnt/lc/open_metadata/extensions/ingestion-1.12.10}"
PYTHON_VERSION="3.10"
SOURCE_SITE_PACKAGES="/usr/python/lib/python${PYTHON_VERSION}/site-packages"
SOURCE_CUSTOMDATABASE="/home/airflow/.local/lib/python${PYTHON_VERSION}/site-packages/metadata/ingestion/source/database/customdatabase"

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "error: required command not found: $1" >&2
    exit 1
  }
}

copy_contents() {
  local source_path="$1"
  local target_path="$2"
  mkdir -p "$target_path"
  docker cp "${container_id}:${source_path}/." "$target_path/"
}

require_command docker

if [[ -z "$TARGET_DIR" || "$TARGET_DIR" == "/" ]]; then
  echo "error: refusing an empty or root extension directory" >&2
  exit 1
fi

docker image inspect "$SOURCE_IMAGE" >/dev/null 2>&1 || {
  echo "error: source image was not found locally: $SOURCE_IMAGE" >&2
  echo "       set OPENMETADATA_EXTENSIONS_SOURCE_IMAGE to the imported combined image" >&2
  exit 1
}
docker image inspect "$RUNTIME_IMAGE" >/dev/null 2>&1 || {
  echo "error: runtime image was not found locally: $RUNTIME_IMAGE" >&2
  exit 1
}

source_arch="$(docker image inspect --format '{{.Architecture}}' "$SOURCE_IMAGE")"
runtime_arch="$(docker image inspect --format '{{.Architecture}}' "$RUNTIME_IMAGE")"
if [[ "$source_arch" != "$runtime_arch" ]]; then
  echo "error: extension source image architecture ($source_arch) does not match runtime image ($runtime_arch)" >&2
  exit 1
fi

mkdir -p "$TARGET_DIR"
staging_dir="$(mktemp -d "${TMPDIR:-/tmp}/openmetadata-ingestion-extensions.XXXXXX")"
container_id=""

cleanup() {
  if [[ -n "$container_id" ]]; then
    docker rm "$container_id" >/dev/null 2>&1 || true
  fi
  rm -rf "$staging_dir"
}
trap cleanup EXIT

mkdir -p \
  "$staging_dir/python/metadata/ingestion/source/database" \
  "$staging_dir/connectors/dameng_connector" \
  "$staging_dir/connectors/kingbase_connector" \
  "$staging_dir/drivers/dameng" \
  "$staging_dir/drivers/kingbase" \
  "$staging_dir/drivers/vastbase"

container_id="$(docker create "$SOURCE_IMAGE")"

# Current extension code and vendor drivers come from the imported combined
# image.  This keeps the one-time extraction independent from the Web source
# tree, while the official 1.12.10 image remains the runtime base.
copy_contents "$SOURCE_CUSTOMDATABASE" \
  "$staging_dir/python/metadata/ingestion/source/database/customdatabase"
copy_contents "/opt/dameng_connector" "$staging_dir/connectors/dameng_connector"
copy_contents "/opt/kingbase_connector" "$staging_dir/connectors/kingbase_connector"
copy_contents "/opt/dameng_jdbc" "$staging_dir/drivers/dameng"
copy_contents "/opt/kingbase_jdbc" "$staging_dir/drivers/kingbase"
copy_contents \
  "$SOURCE_CUSTOMDATABASE/vastbase_connector/drivers" \
  "$staging_dir/drivers/vastbase"

# JayDeBeApi and JPype1 are not part of the official base image.  Vendor only
# these two packages into the same mount so the base image stays untouched.
copy_contents "$SOURCE_SITE_PACKAGES/jaydebeapi" \
  "$staging_dir/python/jaydebeapi"
copy_contents "$SOURCE_SITE_PACKAGES/jpype" \
  "$staging_dir/python/jpype"
copy_contents "$SOURCE_SITE_PACKAGES/JayDeBeApi-1.2.3.dist-info" \
  "$staging_dir/python/JayDeBeApi-1.2.3.dist-info"
copy_contents "$SOURCE_SITE_PACKAGES/jpype1-1.7.1.dist-info" \
  "$staging_dir/python/jpype1-1.7.1.dist-info"
docker cp "${container_id}:${SOURCE_SITE_PACKAGES}/_jpype.so" "$staging_dir/python/"
docker cp "${container_id}:${SOURCE_SITE_PACKAGES}/org.jpype.jar" "$staging_dir/python/"

install -m 0644 \
  "$SCRIPT_DIR/ingestion-extension/runtime-python/sitecustomize.py" \
  "$staging_dir/python/sitecustomize.py"
install -m 0644 \
  "$SCRIPT_DIR/ingestion-extension/README.md" \
  "$staging_dir/README.md"

find "$staging_dir" -type d -name __pycache__ -prune -exec rm -rf {} +

for required_file in \
  "$staging_dir/python/sitecustomize.py" \
  "$staging_dir/python/jaydebeapi/__init__.py" \
  "$staging_dir/python/jpype/__init__.py" \
  "$staging_dir/python/_jpype.so" \
  "$staging_dir/python/org.jpype.jar" \
  "$staging_dir/python/JayDeBeApi-1.2.3.dist-info/METADATA" \
  "$staging_dir/python/jpype1-1.7.1.dist-info/METADATA" \
  "$staging_dir/python/metadata/ingestion/source/database/customdatabase/service_spec.py" \
  "$staging_dir/python/metadata/ingestion/source/database/customdatabase/vastbase_connector/connection.py" \
  "$staging_dir/connectors/dameng_connector/connection.py" \
  "$staging_dir/connectors/kingbase_connector/connection.py" \
  "$staging_dir/drivers/dameng/DmJdbcDriver8.jar" \
  "$staging_dir/drivers/kingbase/kingbase8-8.6.0.jar" \
  "$staging_dir/drivers/vastbase/Vastbase-G100-2.16_pg_2026062910.jar"; do
  [[ -f "$required_file" ]] || {
    echo "error: source image did not provide required extension file: $required_file" >&2
    exit 1
  }
done

cp -a "$staging_dir/." "$TARGET_DIR/"
chmod -R a+rX "$TARGET_DIR"

echo "installed OpenMetadata ingestion extension bundle: $TARGET_DIR"
echo "runtime image: $RUNTIME_IMAGE ($runtime_arch)"
echo "source image: $SOURCE_IMAGE"
echo "mount target: /opt/om-extensions:ro"
echo "next: run tools/openmetadata/verify-ingestion-extensions.sh"
