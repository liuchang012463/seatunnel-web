#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage:
  install-duckdb-web-runtime.sh <web-runtime-package.tar.gz> <web-extension-root>

The package platform must match the Web API runtime. Pass the absolute extension
root used by SEATUNNEL_WEB_DUCKDB_CATALOG_EXTENSION_DIR. Stop Web API before
running this script; it does not restart Web API or modify Engine directories.
EOF
}

if [[ $# -ne 2 ]]; then
  usage
  exit 2
fi

package_path=$1
web_extension_root=$2
if [[ ! -f "$package_path" || ! -r "$package_path" ]]; then
  echo "Web runtime package is missing or unreadable: $package_path" >&2
  exit 2
fi
if [[ "$web_extension_root" != /* ]]; then
  echo "Web extension root must be an absolute path: $web_extension_root" >&2
  exit 2
fi
for command_name in python3 sha256sum install cmp cp mv; do
  command -v "$command_name" >/dev/null || {
    echo "$command_name is required to install the Web runtime safely" >&2
    exit 2
  }
done

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/seatunnel-duckdb-web-install.XXXXXX")
stage_dir=
backup_dir=
transaction_started=0
committed=0
backup_has_content=0

restore_web_extension() {
  if [[ -f "$backup_dir/sqlite_scanner.duckdb_extension" ]]; then
    if [[ -e "$target_extension" || -L "$target_extension" ]]; then
      if [[ ! -f "$target_extension" || -L "$target_extension" ]]; then
        echo "Cannot safely restore over non-regular path $target_extension" >&2
        return 1
      fi
      cmp -s "$target_extension" "$backup_dir/sqlite_scanner.duckdb_extension" && return 0
    fi
    cp -p -- "$backup_dir/sqlite_scanner.duckdb_extension" "$target_extension" || return 1
    cmp -s "$backup_dir/sqlite_scanner.duckdb_extension" "$target_extension" || return 1
    return 0
  fi

  [[ -e "$target_extension" || -L "$target_extension" ]] || return 0
  if [[ -f "$target_extension" && ! -L "$target_extension" ]] \
      && cmp -s "$target_extension" "$package_extension"; then
    rm -f -- "$target_extension" || return 1
    return 0
  fi
  echo "Cannot identify a prior Web extension copy; preserving $target_extension" >&2
  return 1
}

cleanup() {
  local exit_code=$?
  trap - EXIT
  if ((transaction_started == 1 && committed == 0)); then
    echo "Web extension switch failed; restoring the previous extension..." >&2
    if restore_web_extension; then
      echo "Web extension rollback completed." >&2
      rm -rf -- "$stage_dir"
      if ((backup_has_content == 1)); then
        echo "Verified Web extension backup retained at $backup_dir" >&2
      else
        rm -rf -- "$backup_dir"
      fi
    else
      echo "Web extension rollback was incomplete; recovery files are retained:" >&2
      echo "  transaction: $stage_dir" >&2
      echo "  verified backup: $backup_dir" >&2
      if ((exit_code == 0)); then
        exit_code=1
      fi
    fi
  elif ((committed == 1)); then
    rm -rf -- "$stage_dir"
    if ((backup_has_content == 1)); then
      echo "Previous Web extension retained at $backup_dir"
    else
      rm -rf -- "$backup_dir"
    fi
  else
    [[ -z "$stage_dir" ]] || rm -rf -- "$stage_dir"
    [[ -z "$backup_dir" ]] || rm -rf -- "$backup_dir"
  fi
  rm -rf -- "$work_dir"
  exit "$exit_code"
}
trap cleanup EXIT

# Validate the platform-specific package manifest and extract only the SQLite extension.
platform=$(python3 - "$package_path" "$work_dir" <<'PY'
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tarfile

package = Path(sys.argv[1])
destination = Path(sys.argv[2])
platforms = ("linux_amd64", "linux_arm64")
common = {"manifest.json", "SHA256SUMS"}
expected_by_platform = {
    platform: common | {f"extensions/v1.5.6/{platform}/sqlite_scanner.duckdb_extension"}
    for platform in platforms
}
with tarfile.open(package, "r:gz") as archive:
    members = archive.getmembers()
    names = [member.name for member in members]
    if len(names) != len(set(names)):
        raise SystemExit("Web package contains duplicate archive entries")
    matched_platform = next(
        (platform for platform, expected in expected_by_platform.items() if set(names) == expected),
        None,
    )
    if matched_platform is None:
        raise SystemExit("Web package has unexpected or missing archive entries")
    for member in members:
        if not member.isfile():
            raise SystemExit(f"Web package entry is not a regular file: {member.name}")
        target = destination / member.name
        target.parent.mkdir(parents=True, exist_ok=True)
        with archive.extractfile(member) as source, target.open("wb") as output:
            shutil.copyfileobj(source, output)

manifest = json.loads((destination / "manifest.json").read_text(encoding="utf-8"))
if manifest.get("target") != "web":
    raise SystemExit("Package target must be web")
if manifest.get("duckdb_version") != "1.5.6":
    raise SystemExit("Package DuckDB version must be 1.5.6")
if manifest.get("jdbc_artifact_version") != "1.5.6.0":
    raise SystemExit("Package JDBC artifact version must match the Web API dependency 1.5.6.0")
if manifest.get("platform") != matched_platform:
    raise SystemExit("Package platform does not match its extension path")
relative_path = f"extensions/v1.5.6/{matched_platform}/sqlite_scanner.duckdb_extension"
if set(manifest.get("sha256", {})) != {relative_path}:
    raise SystemExit("Web package manifest must contain only sqlite_scanner")
actual = hashlib.sha256((destination / relative_path).read_bytes()).hexdigest()
if actual != manifest["sha256"][relative_path]:
    raise SystemExit("Web extension SHA-256 mismatch")
expected_sums = f"{actual}  {relative_path}\n"
if (destination / "SHA256SUMS").read_text(encoding="ascii") != expected_sums:
    raise SystemExit("Web SHA256SUMS does not match its manifest")
print(matched_platform)
PY
)
(cd "$work_dir" && sha256sum --check SHA256SUMS)

mkdir -p "$web_extension_root/v1.5.6/$platform"
stage_dir=$(mktemp -d "$web_extension_root/.duckdb-web-runtime-stage.XXXXXX")
backup_dir=$(mktemp -d "$web_extension_root/duckdb-web-runtime-backup.XXXXXX")
package_extension="$work_dir/extensions/v1.5.6/$platform/sqlite_scanner.duckdb_extension"
staged_extension="$stage_dir/sqlite_scanner.duckdb_extension"
target_extension="$web_extension_root/v1.5.6/$platform/sqlite_scanner.duckdb_extension"

# Stage and verify the new extension on the same filesystem before changing Web's runtime.
install -m 0644 "$package_extension" "$staged_extension"
cmp -s "$package_extension" "$staged_extension" || {
  echo "Staged Web extension verification failed" >&2
  exit 1
}
if [[ -e "$target_extension" || -L "$target_extension" ]]; then
  if [[ ! -f "$target_extension" || -L "$target_extension" ]]; then
    echo "Refusing to replace a non-regular Web extension path: $target_extension" >&2
    exit 1
  fi
  cp -p -- "$target_extension" "$backup_dir/sqlite_scanner.duckdb_extension"
  cmp -s "$target_extension" "$backup_dir/sqlite_scanner.duckdb_extension" || {
    echo "Web extension backup verification failed" >&2
    exit 1
  }
  backup_has_content=1
fi

transaction_started=1
mv -T -- "$staged_extension" "$target_extension"
cmp -s "$package_extension" "$target_extension"
committed=1

echo "Installed DuckDB v1.5.6 sqlite_scanner for Web platform $platform."
echo "Restart Web API before validating SQLite catalog and preview."
