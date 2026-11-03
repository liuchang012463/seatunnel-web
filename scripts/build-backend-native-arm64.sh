#!/usr/bin/env bash

set -Eeuo pipefail

usage() {
    cat <<'USAGE'
Usage: scripts/build-backend-native-arm64.sh BUNDLE_DIRECTORY [IMAGE_TAG]

Builds a local linux/arm64 backend image with validated Dameng and Kingbase
native clients. The default tag is seatunnel-web-api:1.0.0-native-arm64.
No image is pushed, deployed, or used to restart a service.
USAGE
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
    usage >&2
    exit 2
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
BUNDLE_INPUT="$1"
IMAGE_TAG="${2:-seatunnel-web-api:1.0.0-native-arm64}"
BACKEND_BASE_IMAGE="eclipse-temurin:21-jre-jammy"

if [[ "$IMAGE_TAG" != *native-arm64* ]]; then
    printf 'Image tag must contain native-arm64: %s\n' "$IMAGE_TAG" >&2
    exit 2
fi

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        printf 'Required command not found: %s\n' "$1" >&2
        exit 1
    fi
}

require_command docker
require_command python3
require_command sha256sum
require_command git

if [[ ! -d "$BUNDLE_INPUT" ]]; then
    printf 'Bundle directory not found: %s\n' "$BUNDLE_INPUT" >&2
    exit 1
fi
BUNDLE_INPUT="$(cd -- "$BUNDLE_INPUT" && pwd -P)"

shopt -s nullglob
DIST_FILES=("$SCRIPT_DIR"/seatunnel-web-dist/target/seatunnel-web-*.tar.gz)
shopt -u nullglob
if (( ${#DIST_FILES[@]} != 1 )); then
    printf 'Expected exactly one distribution archive under seatunnel-web-dist/target; found %s.\n' \
        "${#DIST_FILES[@]}" >&2
    exit 1
fi

docker info >/dev/null
if ! docker image inspect "$BACKEND_BASE_IMAGE" >/dev/null 2>&1; then
    printf 'Required local base image was not found: %s\n' "$BACKEND_BASE_IMAGE" >&2
    printf 'This script does not pull base images automatically.\n' >&2
    exit 1
fi
BASE_PLATFORM="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$BACKEND_BASE_IMAGE")"
if [[ "$BASE_PLATFORM" != linux/arm64 ]]; then
    printf 'Backend base image must be linux/arm64, found %s\n' "$BASE_PLATFORM" >&2
    exit 1
fi
BASE_IMAGE_ID="$(docker image inspect --format '{{.Id}}' "$BACKEND_BASE_IMAGE")"

BUILD_CONTEXT="$(mktemp -d)"
cleanup() {
    rm -rf -- "$BUILD_CONTEXT"
}
trap cleanup EXIT

mkdir -p \
    "$BUILD_CONTEXT/seatunnel-web-dist/target" \
    "$BUILD_CONTEXT/native-client-bundle" \
    "$BUILD_CONTEXT/native-arm64"
cp "$SCRIPT_DIR/Dockerfile" "$BUILD_CONTEXT/Dockerfile"
cp "${DIST_FILES[0]}" "$BUILD_CONTEXT/seatunnel-web-dist/target/"
cp -a "$BUNDLE_INPUT/." "$BUILD_CONTEXT/native-client-bundle/"
cp "$SCRIPT_DIR/scripts/native-arm64/dimp" "$BUILD_CONTEXT/native-arm64/dimp"
cp "$SCRIPT_DIR/scripts/native-arm64/sys_restore" "$BUILD_CONTEXT/native-arm64/sys_restore"

python3 "$SCRIPT_DIR/scripts/native-arm64/validate_bundle.py" \
    "$BUILD_CONTEXT/native-client-bundle"
BUNDLE_SHA256="$(sha256sum "$BUILD_CONTEXT/native-client-bundle/SHA256SUMS" | awk '{print $1}')"
VCS_REF="$(git -C "$SCRIPT_DIR" rev-parse --short HEAD 2>/dev/null || printf unknown)"
if [[ -n "$(git -C "$SCRIPT_DIR" status --porcelain 2>/dev/null || true)" ]]; then
    VCS_REF="${VCS_REF}-dirty"
fi
BUILD_DATE="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"

cat <<INFO

Native ARM64 backend build
--------------------------
Platform           : linux/arm64
Target             : backend-native-arm64
Image tag          : $IMAGE_TAG
Backend base image : $BACKEND_BASE_IMAGE ($BASE_IMAGE_ID)
Bundle checksums   : $BUNDLE_SHA256
Source revision    : $VCS_REF
Distribution       : ${DIST_FILES[0]}
INFO

DOCKER_BUILDKIT=0 docker build \
    --platform linux/arm64 \
    --pull=false \
    --target backend-native-arm64 \
    --build-arg "VERSION=1.0.0" \
    --build-arg "VCS_REF=$VCS_REF" \
    --build-arg "BUILD_DATE=$BUILD_DATE" \
    --build-arg "NATIVE_BUNDLE_SHA256=$BUNDLE_SHA256" \
    --build-arg "BASE_IMAGE_ID=$BASE_IMAGE_ID" \
    --tag "$IMAGE_TAG" \
    "$BUILD_CONTEXT"

IMAGE_PLATFORM="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$IMAGE_TAG")"
if [[ "$IMAGE_PLATFORM" != linux/arm64 ]]; then
    printf 'Built image has unexpected platform: %s\n' "$IMAGE_PLATFORM" >&2
    exit 1
fi
printf '\nLocal image ready: %s (%s)\n' "$IMAGE_TAG" "$IMAGE_PLATFORM"
