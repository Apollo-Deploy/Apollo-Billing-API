#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Pre-flight: verify minimum system requirements before touching anything.
# ─────────────────────────────────────────────────────────────────────────────

echo "${_group}Checking requirements ..."

MIN_DOCKER_VERSION="24.0.0"
MIN_COMPOSE_VERSION="2.20.0"

# ---------------------------------------------------------------------------
# Version comparison (pure-bash, works on macOS bash 3)
# Returns 0 (true) if $1 >= $2 (semver strings without leading "v").
# ---------------------------------------------------------------------------
_vergte() {
  local a="$1" b="$2"
  local IFS=.
  # shellcheck disable=SC2206
  local va=($a) vb=($b)
  local i
  for i in 0 1 2; do
    local na="${va[$i]:-0}" nb="${vb[$i]:-0}"
    if (( na > nb )); then return 0; fi
    if (( na < nb )); then return 1; fi
  done
  return 0
}

# ---------------------------------------------------------------------------
# Required CLI tools
# ---------------------------------------------------------------------------
for tool in docker openssl; do
  if ! command -v "$tool" &>/dev/null; then
    error "Required tool not found: ${tool}"
    exit 1
  fi
done
info "Found required CLI tools (docker, openssl)"

# ---------------------------------------------------------------------------
# Docker daemon
# ---------------------------------------------------------------------------
DOCKER_VERSION=$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo "")
if [[ -z "$DOCKER_VERSION" ]]; then
  error "Cannot reach the Docker daemon. Is Docker running?"
  exit 1
fi
if ! _vergte "${DOCKER_VERSION//v/}" "$MIN_DOCKER_VERSION"; then
  error "Docker >= ${MIN_DOCKER_VERSION} required (found ${DOCKER_VERSION})."
  exit 1
fi
info "Found Docker ${DOCKER_VERSION}"

# ---------------------------------------------------------------------------
# Docker Compose
# ---------------------------------------------------------------------------
DC_CMD=$(detect_compose_cmd)
export DC_CMD
COMPOSE_VERSION=$($DC_CMD version --short 2>/dev/null | tr -d 'v' || echo "")
if [[ -z "$COMPOSE_VERSION" ]]; then
  error "Could not determine Docker Compose version."
  exit 1
fi
if ! _vergte "${COMPOSE_VERSION//v/}" "$MIN_COMPOSE_VERSION"; then
  error "Docker Compose >= ${MIN_COMPOSE_VERSION} required (found ${COMPOSE_VERSION})."
  exit 1
fi
info "Found Docker Compose ${COMPOSE_VERSION}"

success "Requirements OK"
echo "${_endgroup}"
