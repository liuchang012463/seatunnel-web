#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage:
  install-duckdb-runtime.sh <runtime-package.tar.gz> <engine-lib-dir> <extension-root>

All installation paths must be explicit absolute paths. The extension root must
be the Engine-local directory configured as SEATUNNEL_WEB_DUCKDB_EXTENSION_DIR.
Stop the Engine before running this script; it does not restart Engine processes.
EOF
}

if [[ $# -ne 3 ]]; then
  usage
  exit 2
fi

package_path=$1
engine_lib_dir=$2
extension_root=$3

if [[ ! -f "$package_path" || ! -r "$package_path" ]]; then
  echo "Runtime package is missing or unreadable: $package_path" >&2
  exit 2
fi
for directory in "$engine_lib_dir" "$extension_root"; do
  if [[ "$directory" != /* ]]; then
    echo "Installation paths must be absolute: $directory" >&2
    exit 2
  fi
done
if [[ ! -d "$engine_lib_dir" ]]; then
  echo "Engine lib directory does not exist: $engine_lib_dir" >&2
  exit 2
fi
for command_name in python3 sha256sum install cmp cp mv; do
  command -v "$command_name" >/dev/null || {
    echo "$command_name is required to install the runtime safely" >&2
    exit 2
  }
done

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/seatunnel-duckdb-install.XXXXXX")
lib_stage_dir=
extension_stage_dir=
backup_dir=
transaction_started=0
committed=0
backup_has_content=0

restore_target() {
  local target=$1
  local old_backup=$2
  local package_file=${3:-}

  if [[ -f "$old_backup" ]]; then
    if [[ -e "$target" || -L "$target" ]]; then
      if [[ ! -f "$target" || -L "$target" ]]; then
        echo "Cannot safely restore over non-regular path $target" >&2
        return 1
      fi
      cmp -s "$target" "$old_backup" && return 0
    fi
    # Use a verified copy for rollback so a failed mv switch cannot fail the restore too.
    cp -p -- "$old_backup" "$target" || return 1
    cmp -s "$old_backup" "$target" || {
      echo "Restored content verification failed: $target" >&2
      return 1
    }
    return 0
  fi

  [[ -e "$target" || -L "$target" ]] || return 0
  if [[ -n "$package_file" && -f "$target" && ! -L "$target" ]] \
      && cmp -s "$target" "$package_file"; then
    rm -f -- "$target" || return 1
    return 0
  fi
  echo "Cannot safely identify a prior copy for $target; preserving it" >&2
  return 1
}

rollback_transaction() {
  local failed=0
  local old_jar_backup="$backup_dir/jars/duckdb_jdbc-1.5.6.0.jar"
  local old_httpfs_backup="$backup_dir/extensions/httpfs.duckdb_extension"
  local old_sqlite_backup="$backup_dir/extensions/sqlite_scanner.duckdb_extension"
  local backup_file

  # Restore from the external verified copies, not by renaming files into a failing destination.
  restore_target "$engine_lib_dir/duckdb_jdbc-1.5.6.0.jar" \
    "$old_jar_backup" "$work_dir/lib/duckdb_jdbc-1.5.6.0.jar" || failed=1
  for backup_file in "$backup_dir"/jars/duckdb_jdbc-*.jar; do
    [[ -e "$backup_file" ]] || continue
    [[ "$backup_file" == "$old_jar_backup" ]] && continue
    restore_target "$engine_lib_dir/$(basename "$backup_file")" "$backup_file" || failed=1
  done
  restore_target \
    "$extension_root/v1.5.6/linux_arm64/httpfs.duckdb_extension" \
    "$old_httpfs_backup" \
    "$work_dir/extensions/v1.5.6/linux_arm64/httpfs.duckdb_extension" || failed=1
  restore_target \
    "$extension_root/v1.5.6/linux_arm64/sqlite_scanner.duckdb_extension" \
    "$old_sqlite_backup" \
    "$work_dir/extensions/v1.5.6/linux_arm64/sqlite_scanner.duckdb_extension" || failed=1
  return "$failed"
}

cleanup() {
  local exit_code=$?
  trap - EXIT

  if ((transaction_started == 1 && committed == 0)); then
    echo "Runtime switch failed; restoring the previous Engine files..." >&2
    if rollback_transaction; then
      echo "Rollback completed: the prior JDBC JAR and extensions are restored." >&2
      rm -rf -- "$lib_stage_dir" "$extension_stage_dir"
      if ((backup_has_content == 0)); then
        rm -rf -- "$backup_dir"
      else
        echo "Verified rollback copies retained at $backup_dir" >&2
      fi
    else
      echo "Automatic rollback was incomplete; recovery files are retained:" >&2
      echo "  transaction: $lib_stage_dir and $extension_stage_dir" >&2
      echo "  verified backups: $backup_dir" >&2
      if ((exit_code == 0)); then
        exit_code=1
      fi
    fi
  elif ((committed == 1)); then
    rm -rf -- "$lib_stage_dir" "$extension_stage_dir"
    if ((backup_has_content == 1)); then
      echo "Previous JDBC JAR/extensions retained at $backup_dir"
    else
      rm -rf -- "$backup_dir"
    fi
  else
    [[ -z "$lib_stage_dir" ]] || rm -rf -- "$lib_stage_dir"
    [[ -z "$extension_stage_dir" ]] || rm -rf -- "$extension_stage_dir"
    [[ -z "$backup_dir" ]] || rm -rf -- "$backup_dir"
  fi

  rm -rf -- "$work_dir"
  exit "$exit_code"
}
trap cleanup EXIT

# Extract only the expected regular files and verify package hashes before touching Engine paths.
python3 - "$package_path" "$work_dir" <<'PY'
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tarfile

package = Path(sys.argv[1])
destination = Path(sys.argv[2])
expected_runtime_files = {
    "lib/duckdb_jdbc-1.5.6.0.jar",
    "extensions/v1.5.6/linux_arm64/httpfs.duckdb_extension",
    "extensions/v1.5.6/linux_arm64/sqlite_scanner.duckdb_extension",
}
expected_members = expected_runtime_files | {"manifest.json", "SHA256SUMS"}

with tarfile.open(package, "r:gz") as archive:
    members = archive.getmembers()
    names = [member.name for member in members]
    if len(names) != len(set(names)) or set(names) != expected_members:
        raise SystemExit("Package has unexpected, missing, or duplicate archive entries")
    for member in members:
        if not member.isfile():
            raise SystemExit(f"Package entry is not a regular file: {member.name}")
        target = destination / member.name
        target.parent.mkdir(parents=True, exist_ok=True)
        with archive.extractfile(member) as source, target.open("wb") as output:
            shutil.copyfileobj(source, output)

manifest = json.loads((destination / "manifest.json").read_text(encoding="utf-8"))
if manifest.get("target") != "engine":
    raise SystemExit("Package target must be engine")
if manifest.get("duckdb_version") != "1.5.6":
    raise SystemExit("Package DuckDB version must be 1.5.6")
if manifest.get("jdbc_artifact_version") != "1.5.6.0":
    raise SystemExit("Package JDBC artifact must be 1.5.6.0")
if manifest.get("platform") != "linux_arm64":
    raise SystemExit("This installer currently supports the linux_arm64 Engine package only")
if set(manifest.get("sha256", {})) != expected_runtime_files:
    raise SystemExit("Package manifest file list does not match the supported runtime")

checksum_lines = []
for relative_path in sorted(expected_runtime_files):
    actual = hashlib.sha256((destination / relative_path).read_bytes()).hexdigest()
    expected = manifest["sha256"][relative_path]
    if actual != expected:
        raise SystemExit(f"SHA-256 mismatch for {relative_path}")
    checksum_lines.append(f"{expected}  {relative_path}")
expected_sums = "\n".join(checksum_lines) + "\n"
actual_sums = (destination / "SHA256SUMS").read_text(encoding="ascii")
if actual_sums != expected_sums:
    raise SystemExit("SHA256SUMS does not match the package manifest")
PY

(cd "$work_dir" && sha256sum --check SHA256SUMS)

mkdir -p "$extension_root/v1.5.6/linux_arm64"
engine_home=$(cd "$engine_lib_dir/.." && pwd -P)
lib_stage_dir=$(mktemp -d "$engine_lib_dir/.duckdb-runtime-stage.XXXXXX")
extension_stage_dir=$(mktemp -d "$extension_root/.duckdb-runtime-stage.XXXXXX")
backup_dir=$(mktemp -d "$engine_home/duckdb-runtime-backup.XXXXXX")
mkdir -p "$lib_stage_dir/old-jars" "$extension_stage_dir/old-extensions"
mkdir -p "$backup_dir/jars" "$backup_dir/extensions"

package_jar="$work_dir/lib/duckdb_jdbc-1.5.6.0.jar"
package_httpfs="$work_dir/extensions/v1.5.6/linux_arm64/httpfs.duckdb_extension"
package_sqlite="$work_dir/extensions/v1.5.6/linux_arm64/sqlite_scanner.duckdb_extension"
stage_jar="$lib_stage_dir/duckdb_jdbc-1.5.6.0.jar"
stage_httpfs="$extension_stage_dir/httpfs.duckdb_extension"
stage_sqlite="$extension_stage_dir/sqlite_scanner.duckdb_extension"
target_httpfs="$extension_root/v1.5.6/linux_arm64/httpfs.duckdb_extension"
target_sqlite="$extension_root/v1.5.6/linux_arm64/sqlite_scanner.duckdb_extension"

# Copy and verify all new artifacts on their destination filesystems before the switch begins.
install -m 0644 "$package_jar" "$stage_jar"
install -m 0644 "$package_httpfs" "$stage_httpfs"
install -m 0644 "$package_sqlite" "$stage_sqlite"
cmp -s "$package_jar" "$stage_jar" || { echo "Staged JDBC JAR verification failed" >&2; exit 1; }
cmp -s "$package_httpfs" "$stage_httpfs" || { echo "Staged httpfs verification failed" >&2; exit 1; }
cmp -s "$package_sqlite" "$stage_sqlite" || { echo "Staged sqlite_scanner verification failed" >&2; exit 1; }

# Save verified recovery copies outside the Engine classpath before moving any live file.
shopt -s nullglob
existing_jars=("$engine_lib_dir"/duckdb_jdbc-*.jar)
for old_jar in "${existing_jars[@]}"; do
  if [[ ! -f "$old_jar" || -L "$old_jar" ]]; then
    echo "Refusing to replace a non-regular DuckDB JDBC path: $old_jar" >&2
    exit 1
  fi
  cp -p -- "$old_jar" "$backup_dir/jars/$(basename "$old_jar")"
  cmp -s "$old_jar" "$backup_dir/jars/$(basename "$old_jar")" \
    || { echo "JDBC backup verification failed: $old_jar" >&2; exit 1; }
  backup_has_content=1
done
for extension_file in "$target_httpfs" "$target_sqlite"; do
  if [[ -e "$extension_file" || -L "$extension_file" ]]; then
    if [[ ! -f "$extension_file" || -L "$extension_file" ]]; then
      echo "Refusing to replace a non-regular extension path: $extension_file" >&2
      exit 1
    fi
    cp -p -- "$extension_file" "$backup_dir/extensions/$(basename "$extension_file")"
    cmp -s "$extension_file" "$backup_dir/extensions/$(basename "$extension_file")" \
      || { echo "Extension backup verification failed: $extension_file" >&2; exit 1; }
    backup_has_content=1
  fi
done

# From this point every old file has a verified recovery copy; EXIT restores on any failure.
transaction_started=1
for old_jar in "${existing_jars[@]}"; do
  mv -T -- "$old_jar" "$lib_stage_dir/old-jars/$(basename "$old_jar")"
done
for extension_file in "$target_httpfs" "$target_sqlite"; do
  if [[ -e "$extension_file" ]]; then
    mv -T -- "$extension_file" "$extension_stage_dir/old-extensions/$(basename "$extension_file")"
  fi
done

# Each rename stays within its destination filesystem. A failed rename triggers full rollback.
mv -T -- "$stage_jar" "$engine_lib_dir/duckdb_jdbc-1.5.6.0.jar"
mv -T -- "$stage_httpfs" "$target_httpfs"
mv -T -- "$stage_sqlite" "$target_sqlite"
cmp -s "$package_jar" "$engine_lib_dir/duckdb_jdbc-1.5.6.0.jar"
cmp -s "$package_httpfs" "$target_httpfs"
cmp -s "$package_sqlite" "$target_sqlite"
committed=1

echo "Installed DuckDB JDBC 1.5.6.0 and v1.5.6/linux_arm64 extensions."
echo "Restart all Engine members before submitting validation jobs."
