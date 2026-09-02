#!/usr/bin/env bash
set -euo pipefail

# Remove only generated local artifacts from a SeaTunnel Web checkout.
#
# The default mode is a dry-run. Use --apply after reviewing the printed
# paths. Runtime logs and profiles are deliberately excluded unless the
# caller also supplies --include-runtime-data because they can contain audit
# information.

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
apply=false
include_runtime_data=false

usage() {
    cat <<'EOF'
Usage: scripts/clean-generated.sh [--dry-run] [--apply]
       [--include-runtime-data]

Default: print generated artifacts without deleting anything.
--apply: delete the generated paths printed by the dry-run.
--include-runtime-data: also delete the explicitly listed logs/profile
                        directories (requires --apply).
EOF
}

for arg in "$@"; do
    case "$arg" in
        --dry-run)
            apply=false
            ;;
        --apply)
            apply=true
            ;;
        --include-runtime-data)
            include_runtime_data=true
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $arg" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [[ "$include_runtime_data" == true && "$apply" != true ]]; then
    echo "--include-runtime-data requires --apply; refusing to remove anything" >&2
    exit 2
fi

generated_paths=()
runtime_paths=(
    "$repo_root/logs"
    "$repo_root/profile"
    "$repo_root/seatunnel-web-api/logs"
    "$repo_root/seatunnel-web-api/profile"
)

add_if_present() {
    local path="$1"
    if [[ -e "$path" || -L "$path" ]]; then
        generated_paths+=("$path")
    fi
}

# Explicit top-level generated directories.
add_if_present "$repo_root/node_modules"
add_if_present "$repo_root/seatunnel-web-ui/node_modules"
add_if_present "$repo_root/seatunnel-web-ui/dist"

# Umi's generated source metadata is always directly under src. Do not use a
# recursive wildcard here: source directories with a similar name must not be
# removed accidentally.
while IFS= read -r -d '' path; do
    generated_paths+=("$path")
done < <(find "$repo_root/seatunnel-web-ui/src" -mindepth 1 -maxdepth 1 -type d -name '.umi*' -print0 2>/dev/null)

# Maven output, while pruning all known dependency/control directories before
# traversal. This includes the root target and every module target, but never
# source modules, tmp, .agents, docs, or the OpenMetadata extension.
while IFS= read -r -d '' path; do
    generated_paths+=("$path")
done < <(
    find "$repo_root" \
        \( -path "$repo_root/.git" \
        -o -path "$repo_root/node_modules" \
        -o -path "$repo_root/seatunnel-web-ui/node_modules" \
        -o -path "$repo_root/.codegraph" \) -prune \
        -o -type d -name target -print0
)

# Screenshots and the Playwright MCP session directory created by the local
# desktop verification are generated artifacts, not project sources.  The
# CodeGraph index is deliberately preserved because this workspace uses it
# for code navigation and it is not a disposable build output.
add_if_present "$repo_root/.playwright-mcp"
for path in "$repo_root"/lake-warehouse-pass-*.png; do
    [[ -e "$path" ]] && generated_paths+=("$path")
done

if [[ "$apply" == true ]]; then
    echo "Applying generated-artifact cleanup under $repo_root"
else
    echo "Dry-run only; no files will be removed. Root: $repo_root"
fi

if ((${#generated_paths[@]} == 0)); then
    echo "No generated artifacts found."
else
    for path in "${generated_paths[@]}"; do
        if [[ "$apply" == true ]]; then
            printf 'REMOVE %s\n' "$path"
            rm -rf -- "$path"
        else
            printf 'WOULD REMOVE %s\n' "$path"
        fi
    done
fi

echo
echo "Runtime data is preserved by default (may contain audit records):"
for path in "${runtime_paths[@]}"; do
    [[ -e "$path" || -L "$path" ]] && printf 'PRESERVE %s\n' "$path"
done

if [[ "$include_runtime_data" == true ]]; then
    echo
    echo "Removing explicitly requested runtime data:"
    for path in "${runtime_paths[@]}"; do
        if [[ -e "$path" || -L "$path" ]]; then
            printf 'REMOVE %s\n' "$path"
            rm -rf -- "$path"
        fi
    done
fi

echo
echo "Preserved source and operational paths: seatunnel-web-common, seatunnel-web-dao,"
echo "seatunnel-web-dao-plugin, openmetadata-ingestion-extension, .mvn, docs, .vscode,"
echo "tmp, .agents, .codegraph."
