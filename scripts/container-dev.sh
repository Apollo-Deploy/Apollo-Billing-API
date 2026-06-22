#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# container-dev.sh — Apollo Billing
#
# Uses Apple's `container` CLI on Apple Silicon Macs (macOS 26+) instead of
# Docker for building and running the billing service locally.
#
# NOTE: Billing does NOT own Postgres or Redis — those are provided by the
# platform stack. The platform must be running before starting billing.
#
# Usage:
#   ./scripts/container-dev.sh              # Build & run billing service
#   ./scripts/container-dev.sh --build      # Build image only
#   ./scripts/container-dev.sh --stop       # Stop billing container
#   ./scripts/container-dev.sh --status     # Show container status
#   ./scripts/container-dev.sh --force-docker  # Force Docker
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Load .env if present (safely handles multiline values like RSA keys)
if [ -f "$ROOT_DIR/.env" ]; then
  while IFS='=' read -r key value; do
    [[ -z "$key" || "$key" =~ ^[[:space:]]*# ]] && continue
    key="$(echo "$key" | xargs)"
    export "$key=$value"
  done < <(grep -v '^\s*#' "$ROOT_DIR/.env" | grep -v '^\s*$')
fi

# ── Color helpers ────────────────────────────────────────────────────────────

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info()  { echo -e "${BLUE}ℹ${NC}  $*"; }
ok()    { echo -e "${GREEN}✅${NC} $*"; }
warn()  { echo -e "${YELLOW}⚠️${NC}  $*"; }
error() { echo -e "${RED}❌${NC} $*"; }

# ── Runtime detection ────────────────────────────────────────────────────────

detect_runtime() {
  for arg in "$@"; do
    case "$arg" in
      --force-docker) echo "docker"; return ;;
      --force-container)
        if ! command -v container &>/dev/null; then
          error "Apple container CLI not found."
          exit 1
        fi
        echo "container"; return ;;
    esac
  done

  local arch os
  arch="$(uname -m)"
  os="$(uname -s)"

  if [[ "$os" == "Darwin" && "$arch" == "arm64" ]]; then
    if command -v container &>/dev/null && container system status &>/dev/null 2>&1; then
      echo "container"
      return
    fi
  fi

  if command -v docker &>/dev/null; then
    echo "docker"
  else
    error "No container runtime found."
    exit 1
  fi
}

# ── Configuration ────────────────────────────────────────────────────────────

CONTAINER_NAME="apollo-billing"
IMAGE_NAME="apollo-billing:latest"
DEV_IMAGE_NAME="apollo-billing:dev"
PLATFORM_NETWORK="${PLATFORM_NETWORK:-platform_default}"

# ── Apple Container Functions ────────────────────────────────────────────────

container_build() {
  local tag="${1:-$IMAGE_NAME}"
  info "Building billing image ($tag) with Apple container CLI..."
  container build \
    --file "$ROOT_DIR/Dockerfile" \
    --tag "$tag" \
    "$ROOT_DIR"
  ok "Built: $tag"
}

container_run() {
  local mode="${1:-production}"
  local env_value="production"
  local name="$CONTAINER_NAME"
  local image="$IMAGE_NAME"

  if [ "$mode" = "dev" ]; then
    env_value="development"
    name="${CONTAINER_NAME}-dev"
    image="$DEV_IMAGE_NAME"
  fi

  if container list 2>/dev/null | grep -q "$name"; then
    info "Billing already running ($name)"
    return
  fi

  info "Starting billing service ($mode) via Apple container..."
  container run \
    --name "$name" \
    --publish 3040:3040 \
    --env "APOLLO_BILLING_ENV=$env_value" \
    --env "PLATFORM_DB_HOST=${PLATFORM_DB_HOST:-localhost}" \
    --env "PLATFORM_DB_PORT=5432" \
    --env "PLATFORM_DB_NAME=${PLATFORM_DB_NAME:-apollo_deploy_platform}" \
    --env "PLATFORM_DB_USER=${PLATFORM_DB_USER:-billing_app}" \
    --env "PLATFORM_DB_PASSWORD=${PLATFORM_DB_PASSWORD:-}" \
    --env "BILLING_SUPERUSER_PASSWORD=${BILLING_SUPERUSER_PASSWORD:-}" \
    --env "SIGNAL_DB_HOST=${SIGNAL_DB_HOST:-localhost}" \
    --env "SIGNAL_DB_PORT=5432" \
    --env "SIGNAL_DB_NAME=${SIGNAL_DB_NAME:-apollo_deploy_signal}" \
    --env "REDIS_HOST=${REDIS_HOST:-localhost}" \
    --env "REDIS_PORT=${REDIS_PORT:-6379}" \
    --env "REDIS_PASSWORD=${REDIS_PASSWORD:-}" \
    --env "SERVICE_AUTH_SECRET=${SERVICE_AUTH_SECRET:-}" \
    --env "SERVICE_AUTH_AUDIENCE=${SERVICE_AUTH_AUDIENCE:-apollo-billing}" \
    --env "POLAR_WEBHOOK_SECRET=${POLAR_WEBHOOK_SECRET:-}" \
    --env "POLAR_API_KEY=${POLAR_API_KEY:-}" \
    --env "POLAR_API_BASE_URL=${POLAR_API_BASE_URL:-https://api.polar.sh}" \
    --env "BILLING_PORT=3040" \
    --detach \
    "$image"

  ok "Billing ($mode) started → localhost:3040"
}

container_stop() {
  for name in "$CONTAINER_NAME" "${CONTAINER_NAME}-dev"; do
    if container list 2>/dev/null | grep -q "$name"; then
      container stop "$name" 2>/dev/null
      ok "Stopped $name"
    fi
  done
}

container_logs() {
  # Try dev container first, then production
  for name in "${CONTAINER_NAME}-dev" "$CONTAINER_NAME"; do
    if container list 2>/dev/null | grep -q "$name"; then
      container logs --follow "$name"
      return
    fi
  done
  error "Billing is not running."
  exit 1
}

container_status() {
  echo ""
  echo -e "${BLUE}Apollo Billing Status (Apple container)${NC}"
  echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  container list 2>/dev/null | grep "$CONTAINER_NAME" || info "Billing not running."
  echo ""
}

# ── Docker Functions ─────────────────────────────────────────────────────────

docker_compose_cmd() {
  if docker compose version &>/dev/null 2>&1; then
    echo "docker compose"
  elif command -v docker-compose &>/dev/null; then
    echo "docker-compose"
  else
    error "Docker compose not found."
    exit 1
  fi
}

docker_build() {
  info "Building billing image with Docker..."
  docker build -t "$IMAGE_NAME" "$ROOT_DIR"
  ok "Built: $IMAGE_NAME"
}

docker_run() {
  local dc
  dc="$(docker_compose_cmd)"
  $dc -f "$ROOT_DIR/docker-compose.yml" up -d
  ok "Billing started via Docker Compose."
}

docker_run_dev() {
  local dc
  dc="$(docker_compose_cmd)"
  # Use dev profile or set environment override
  APOLLO_BILLING_ENV=development $dc -f "$ROOT_DIR/docker-compose.yml" up -d
  ok "Billing (dev) started via Docker Compose."
}

docker_stop() {
  local dc
  dc="$(docker_compose_cmd)"
  $dc -f "$ROOT_DIR/docker-compose.yml" down
  ok "Billing stopped."
}

docker_logs() {
  local dc
  dc="$(docker_compose_cmd)"
  $dc -f "$ROOT_DIR/docker-compose.yml" logs -f billing
}

docker_status() {
  local dc
  dc="$(docker_compose_cmd)"
  $dc -f "$ROOT_DIR/docker-compose.yml" ps
}

# ── Main ─────────────────────────────────────────────────────────────────────

main() {
  local action="run"
  local mode="production"

  for arg in "$@"; do
    case "$arg" in
      --build)  action="build" ;;
      --dev)    mode="dev" ;;
      --stop)   action="stop" ;;
      --logs)   action="logs" ;;
      --status) action="status" ;;
      --help|-h)
        echo "Usage: $0 [OPTIONS]"
        echo ""
        echo "Build and run the Apollo Billing service using Apple's container"
        echo "CLI (Apple Silicon) or Docker."
        echo ""
        echo "Prerequisites:"
        echo "  The platform stack must be running (provides Postgres & Redis)."
        echo ""
        echo "Options:"
        echo "  --dev               Build & run in development mode (verbose errors, stack traces)"
        echo "  --build             Build image only (don't run)"
        echo "  --stop              Stop billing container"
        echo "  --logs              Tail container logs"
        echo "  --status            Show container status"
        echo "  --force-docker      Force Docker"
        echo "  --force-container   Force Apple container CLI"
        echo "  -h, --help          Show this help"
        exit 0
        ;;
    esac
  done

  local runtime
  runtime="$(detect_runtime "$@")"
  info "Using runtime: $runtime"
  if [ "$mode" = "dev" ]; then
    info "Mode: development"
  fi
  echo ""

  case "$action" in
    build)
      local tag="$IMAGE_NAME"
      [ "$mode" = "dev" ] && tag="$DEV_IMAGE_NAME"
      if [ "$runtime" = "container" ]; then container_build "$tag"; else docker_build; fi
      ;;
    run)
      if [ "$runtime" = "container" ]; then
        local tag="$IMAGE_NAME"
        [ "$mode" = "dev" ] && tag="$DEV_IMAGE_NAME"
        container_build "$tag"
        container_run "$mode"
      else
        if [ "$mode" = "dev" ]; then docker_run_dev; else docker_run; fi
      fi
      ;;
    stop)
      if [ "$runtime" = "container" ]; then container_stop; else docker_stop; fi
      ;;
    logs)
      if [ "$runtime" = "container" ]; then container_logs; else docker_logs; fi
      ;;
    status)
      if [ "$runtime" = "container" ]; then container_status; else docker_status; fi
      ;;
  esac
}

main "$@"
