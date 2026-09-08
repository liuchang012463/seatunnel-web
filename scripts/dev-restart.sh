#!/usr/bin/env bash
# Restart local frontend/backend so verification runs against the latest code.
# Agents MUST use this after modifying backend or frontend runtime source.

# shellcheck source=dev-common.sh
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/dev-common.sh"

"$SCRIPT_DIR/dev-down.sh"
"$SCRIPT_DIR/dev-up.sh"
