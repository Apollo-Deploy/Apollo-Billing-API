#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Pre-flight: verify minimum system requirements before touching anything.
# ─────────────────────────────────────────────────────────────────────────────

echo "${_group}Checking requirements ..."

MIN_DOCKER_VERSION="24.0.0"
MIN_COMPOSE_VERSION="2.20.0"

# ---------------------------------------------------------------------------
# Required CLI tools
# ---------------------------------------------------------------------------
for tool in docker openssl; do
  if ! have_cmd "$tool"; then
    log_error "Required tool not found: ${tool}"
    exit 1
  fi
done
log_info "Found required CLI tools (docker, openssl)"

# ---------------------------------------------------------------------------
# Docker daemon
# ---------------------------------------------------------------------------
DOCKER_VERSION=$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo "")
if [[ -z "$DOCKER_VERSION" ]]; then
  log_error "Cannot reach the Docker daemon. Is Docker running?"
  exit 1
fi
if ! version_gte "${DOCKER_VERSION//v/}" "$MIN_DOCKER_VERSION"; then
  log_error "Docker >= ${MIN_DOCKER_VERSION} required (found ${DOCKER_VERSION})."
  exit 1
fi
log_info "Found Docker ${DOCKER_VERSION}"

# ---------------------------------------------------------------------------
# Docker Compose
# ---------------------------------------------------------------------------
if has_compose_v2; then
  DC_CMD="docker compose"
elif have_cmd docker-compose; then
  DC_CMD="docker-compose"
else
  log_error "Neither 'docker compose' (v2) nor 'docker-compose' (v1) found."
  exit 1
fi
export DC_CMD

COMPOSE_VERSION=$($DC_CMD version --short 2>/dev/null | tr -d 'v' || echo "")
if [[ -z "$COMPOSE_VERSION" ]]; then
  log_error "Could not determine Docker Compose version."
  exit 1
fi
if ! version_gte "${COMPOSE_VERSION//v/}" "$MIN_COMPOSE_VERSION"; then
  log_error "Docker Compose >= ${MIN_COMPOSE_VERSION} required (found ${COMPOSE_VERSION})."
  exit 1
fi
log_info "Found Docker Compose ${COMPOSE_VERSION}"

log_success "Requirements OK"
echo "${_endgroup}"
