#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Build the Apollo Billing Service Docker image.
#
# Uses BuildKit layer cache — only rebuilds changed layers.
# Set APOLLO_NO_CACHE=1 to force a clean build.
# ─────────────────────────────────────────────────────────────────────────────

echo "${_group}Building Docker image ..."

export DOCKER_BUILDKIT=1

BUILD_FLAGS=""
if [[ "${APOLLO_NO_CACHE:-0}" == "1" ]]; then
  BUILD_FLAGS="--no-cache"
  info "Cache disabled (APOLLO_NO_CACHE=1)"
fi

# shellcheck disable=SC2086
$DC_CMD build $BUILD_FLAGS billing

success "Image built."
echo "${_endgroup}"
