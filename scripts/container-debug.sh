#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# container-debug.sh — Apollo Deploy debugging toolkit
#
# Diagnostic commands for troubleshooting containers, networking, databases,
# and service health across the Apollo stack.
#
# Usage:
#   ./scripts/container-debug.sh health        # Check all service health endpoints
#   ./scripts/container-debug.sh ports         # Show what's listening on expected ports
#   ./scripts/container-debug.sh db            # Test database connectivity
#   ./scripts/container-debug.sh redis         # Test Redis connectivity
#   ./scripts/container-debug.sh logs [svc]    # Dump recent logs (all or specific service)
#   ./scripts/container-debug.sh inspect [svc] # Show container details (env, mounts, network)
#   ./scripts/container-debug.sh shell [svc]   # Open a shell in a running container
#   ./scripts/container-debug.sh net           # Network diagnostics (DNS, routes, connectivity)
#   ./scripts/container-debug.sh env [svc]     # Show environment variables for a service
#   ./scripts/container-debug.sh resources     # Show CPU/memory usage of running containers
#   ./scripts/container-debug.sh doctor        # Run all checks and produce a diagnostic report
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Load .env (safely handles multiline values like RSA keys)
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
CYAN='\033[0;36m'
DIM='\033[2m'
NC='\033[0m'

info()    { echo -e "${BLUE}ℹ${NC}  $*"; }
ok()      { echo -e "${GREEN}✔${NC}  $*"; }
warn()    { echo -e "${YELLOW}⚠${NC}  $*"; }
fail()    { echo -e "${RED}✘${NC}  $*"; }
header()  { echo -e "\n${CYAN}━━━ $* ━━━${NC}\n"; }

# ── Runtime detection ────────────────────────────────────────────────────────

RUNTIME=""
detect_runtime() {
  local arch os
  arch="$(uname -m)"
  os="$(uname -s)"

  if [[ "$os" == "Darwin" && "$arch" == "arm64" ]]; then
    if command -v container &>/dev/null && container system status &>/dev/null 2>&1; then
      RUNTIME="container"
      return
    fi
  fi

  if command -v docker &>/dev/null; then
    RUNTIME="docker"
  else
    fail "No container runtime found."
    exit 1
  fi
}

# ── Helpers ──────────────────────────────────────────────────────────────────

list_containers() {
  if [ "$RUNTIME" = "container" ]; then
    container list 2>/dev/null
  else
    docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null
  fi
}

get_container_logs() {
  local name="$1"
  local lines="${2:-50}"
  if [ "$RUNTIME" = "container" ]; then
    container logs --tail "$lines" "$name" 2>/dev/null
  else
    docker logs --tail "$lines" "$name" 2>/dev/null
  fi
}

exec_in_container() {
  local name="$1"
  shift
  if [ "$RUNTIME" = "container" ]; then
    container exec "$name" "$@"
  else
    docker exec -it "$name" "$@"
  fi
}

# ── Port checker ─────────────────────────────────────────────────────────────

check_port() {
  local port="$1"
  local label="$2"
  if lsof -iTCP:"$port" -sTCP:LISTEN &>/dev/null; then
    local pid
    pid="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -1)"
    local proc
    proc="$(ps -p "$pid" -o comm= 2>/dev/null || echo "unknown")"
    ok "$label → :$port (pid $pid, $proc)"
  else
    fail "$label → :$port NOT LISTENING"
  fi
}

# ── Health check ─────────────────────────────────────────────────────────────

cmd_health() {
  header "Service Health Checks"

  local endpoints=(
    "Platform|http://localhost:3000/health"
    "API|http://localhost:3000/health"
    "Signal|http://localhost:3030/signal/health"
    "Billing|http://localhost:3040/health"
  )

  for entry in "${endpoints[@]}"; do
    local label="${entry%%|*}"
    local url="${entry##*|}"
    local status
    status="$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "$url" 2>/dev/null || echo "000")"
    if [ "$status" = "200" ]; then
      ok "$label ($url) → ${GREEN}$status${NC}"
    elif [ "$status" = "000" ]; then
      fail "$label ($url) → ${RED}unreachable${NC}"
    else
      warn "$label ($url) → ${YELLOW}$status${NC}"
    fi
  done
}

# ── Port scan ────────────────────────────────────────────────────────────────

cmd_ports() {
  header "Port Status"

  check_port 3000 "Platform/API"
  check_port 3030 "Signal"
  check_port 3040 "Billing"
  check_port "${DB_PORT_HOST:-5432}" "PostgreSQL"
  check_port "${PGBOUNCER_PORT_HOST:-5433}" "PgBouncer"
  check_port 6379 "Redis"
  check_port 5433 "TimescaleDB"
  check_port 7233 "Temporal"

  echo ""
  info "Full port listing:"
  echo -e "${DIM}"
  lsof -iTCP -sTCP:LISTEN -nP 2>/dev/null | grep -E ":(3000|3002|3030|3040|5432|5433|6379|7233|8233) " || info "No Apollo ports found"
  echo -e "${NC}"
}

# ── Database connectivity ────────────────────────────────────────────────────

cmd_db() {
  header "Database Connectivity"

  local pg_host="${DB_HOST:-localhost}"
  local pg_port="${DB_PORT_HOST:-5432}"
  local pg_user="${POSTGRES_USER:-apollo}"

  # pg_isready check
  if command -v pg_isready &>/dev/null; then
    if pg_isready -h "$pg_host" -p "$pg_port" -U "$pg_user" &>/dev/null; then
      ok "PostgreSQL accepting connections ($pg_host:$pg_port)"
    else
      fail "PostgreSQL not responding ($pg_host:$pg_port)"
    fi
  else
    # Fallback: TCP probe
    if (echo > /dev/tcp/"$pg_host"/"$pg_port") 2>/dev/null; then
      ok "PostgreSQL port open ($pg_host:$pg_port) — install postgresql-client for full check"
    else
      fail "PostgreSQL port closed ($pg_host:$pg_port)"
    fi
  fi

  # Check databases exist
  if command -v psql &>/dev/null && [ -n "${POSTGRES_PASSWORD:-}" ]; then
    info "Checking databases..."
    local dbs
    dbs="$(PGPASSWORD="$POSTGRES_PASSWORD" psql -h "$pg_host" -p "$pg_port" -U "$pg_user" -t -c "SELECT datname FROM pg_database WHERE datname LIKE 'apollo%'" 2>/dev/null || echo "")"
    if [ -n "$dbs" ]; then
      echo "$dbs" | while read -r db; do
        [ -n "$db" ] && ok "  Database: $(echo "$db" | xargs)"
      done
    else
      warn "  No apollo_* databases found (migrations may not have run)"
    fi
  fi

  # TimescaleDB
  local ts_port="${TIMESCALE_PORT:-5433}"
  if (echo > /dev/tcp/localhost/"$ts_port") 2>/dev/null; then
    ok "TimescaleDB port open (localhost:$ts_port)"
  else
    info "TimescaleDB not running (localhost:$ts_port) — may be expected in dev"
  fi
}

# ── Redis connectivity ───────────────────────────────────────────────────────

cmd_redis() {
  header "Redis Connectivity"

  local redis_host="${REDIS_HOST:-localhost}"
  local redis_port="${REDIS_PORT:-6379}"
  local redis_pass="${REDIS_PASSWORD:-}"

  if command -v redis-cli &>/dev/null; then
    local pong
    if [ -n "$redis_pass" ]; then
      pong="$(redis-cli -h "$redis_host" -p "$redis_port" -a "$redis_pass" ping 2>/dev/null)"
    else
      pong="$(redis-cli -h "$redis_host" -p "$redis_port" ping 2>/dev/null)"
    fi

    if [ "$pong" = "PONG" ]; then
      ok "Redis responding ($redis_host:$redis_port)"

      # Show basic info
      local info_output
      if [ -n "$redis_pass" ]; then
        info_output="$(redis-cli -h "$redis_host" -p "$redis_port" -a "$redis_pass" INFO server 2>/dev/null | head -20)"
      else
        info_output="$(redis-cli -h "$redis_host" -p "$redis_port" INFO server 2>/dev/null | head -20)"
      fi
      local version
      version="$(echo "$info_output" | grep "redis_version:" | cut -d: -f2 | tr -d '\r')"
      local mem
      if [ -n "$redis_pass" ]; then
        mem="$(redis-cli -h "$redis_host" -p "$redis_port" -a "$redis_pass" INFO memory 2>/dev/null | grep "used_memory_human:" | cut -d: -f2 | tr -d '\r')"
      else
        mem="$(redis-cli -h "$redis_host" -p "$redis_port" INFO memory 2>/dev/null | grep "used_memory_human:" | cut -d: -f2 | tr -d '\r')"
      fi
      info "  Version: $version"
      info "  Memory: $mem"
    else
      fail "Redis not responding ($redis_host:$redis_port)"
    fi
  else
    # TCP probe fallback
    if (echo > /dev/tcp/"$redis_host"/"$redis_port") 2>/dev/null; then
      ok "Redis port open ($redis_host:$redis_port) — install redis-cli for full check"
    else
      fail "Redis port closed ($redis_host:$redis_port)"
    fi
  fi
}

# ── Logs ─────────────────────────────────────────────────────────────────────

cmd_logs() {
  local service="${1:-}"
  header "Container Logs"

  if [ -n "$service" ]; then
    info "Logs for: $service (last 100 lines)"
    echo ""
    get_container_logs "$service" 100
  else
    # Show recent logs for all apollo containers
    local containers
    if [ "$RUNTIME" = "container" ]; then
      containers="$(container list 2>/dev/null | grep -i "apollo" | awk '{print $1}' || true)"
    else
      containers="$(docker ps --format '{{.Names}}' 2>/dev/null | grep -i "apollo" || true)"
    fi

    if [ -z "$containers" ]; then
      warn "No Apollo containers running."
      return
    fi

    echo "$containers" | while read -r c; do
      [ -z "$c" ] && continue
      echo -e "${CYAN}── $c ──${NC}"
      get_container_logs "$c" 20
      echo ""
    done
  fi
}

# ── Inspect ──────────────────────────────────────────────────────────────────

cmd_inspect() {
  local service="${1:-}"
  header "Container Inspection"

  if [ -z "$service" ]; then
    info "Running Apollo containers:"
    echo ""
    list_containers | grep -i "apollo" || warn "No Apollo containers found."
    echo ""
    info "Usage: $0 inspect <container-name>"
    return
  fi

  if [ "$RUNTIME" = "container" ]; then
    container inspect "$service" 2>/dev/null || fail "Container '$service' not found"
  else
    echo -e "${CYAN}── Status ──${NC}"
    docker inspect --format '
  Name:    {{.Name}}
  Image:   {{.Config.Image}}
  Status:  {{.State.Status}}
  Started: {{.State.StartedAt}}
  Restart: {{.RestartCount}} times
  PID:     {{.State.Pid}}' "$service" 2>/dev/null || { fail "Container '$service' not found"; return; }

    echo ""
    echo -e "${CYAN}── Network ──${NC}"
    docker inspect --format '{{range $net, $conf := .NetworkSettings.Networks}}  {{$net}}: {{$conf.IPAddress}}
{{end}}' "$service" 2>/dev/null

    echo -e "${CYAN}── Port Bindings ──${NC}"
    docker inspect --format '{{range $p, $conf := .NetworkSettings.Ports}}  {{$p}} → {{range $conf}}{{.HostIp}}:{{.HostPort}}{{end}}
{{end}}' "$service" 2>/dev/null

    echo -e "${CYAN}── Volumes ──${NC}"
    docker inspect --format '{{range .Mounts}}  {{.Type}}: {{.Source}} → {{.Destination}} ({{.Mode}})
{{end}}' "$service" 2>/dev/null
  fi
}

# ── Shell ────────────────────────────────────────────────────────────────────

cmd_shell() {
  local service="${1:-}"

  if [ -z "$service" ]; then
    info "Running Apollo containers:"
    echo ""
    list_containers | grep -i "apollo" || warn "No Apollo containers found."
    echo ""
    info "Usage: $0 shell <container-name>"
    return
  fi

  info "Opening shell in $service..."
  if [ "$RUNTIME" = "container" ]; then
    container exec "$service" /bin/sh
  else
    # Try bash first, fall back to sh
    docker exec -it "$service" /bin/bash 2>/dev/null || docker exec -it "$service" /bin/sh
  fi
}

# ── Network diagnostics ──────────────────────────────────────────────────────

cmd_net() {
  header "Network Diagnostics"

  info "Container runtime: $RUNTIME"
  echo ""

  if [ "$RUNTIME" = "docker" ]; then
    echo -e "${CYAN}── Docker Networks ──${NC}"
    docker network ls --format "table {{.Name}}\t{{.Driver}}\t{{.Scope}}" 2>/dev/null | grep -v "^NETWORK" | head -20

    echo ""
    echo -e "${CYAN}── Apollo Network Details ──${NC}"
    for net in $(docker network ls --format '{{.Name}}' | grep -i "apollo\|platform"); do
      echo -e "  ${GREEN}$net${NC}:"
      docker network inspect "$net" --format '{{range .Containers}}    {{.Name}} ({{.IPv4Address}})
{{end}}' 2>/dev/null
    done
  fi

  echo ""
  echo -e "${CYAN}── DNS Resolution ──${NC}"
  for host in localhost host.docker.internal host.containers.internal; do
    local ip
    ip="$(getent hosts "$host" 2>/dev/null | awk '{print $1}' || echo "unresolved")"
    if [ "$ip" != "unresolved" ]; then
      ok "$host → $ip"
    else
      info "$host → not resolved (may be normal)"
    fi
  done

  echo ""
  echo -e "${CYAN}── Inter-service Connectivity ──${NC}"
  local services=(
    "Platform:localhost:3000"
    "Signal:localhost:3030"
    "Billing:localhost:3040"
    "Postgres:localhost:${DB_PORT_HOST:-5432}"
    "Redis:localhost:6379"
  )
  for entry in "${services[@]}"; do
    local label="${entry%%:*}"
    local addr="${entry#*:}"
    local host="${addr%%:*}"
    local port="${addr##*:}"
    if (echo > /dev/tcp/"$host"/"$port") 2>/dev/null; then
      ok "$label ($addr) → reachable"
    else
      fail "$label ($addr) → unreachable"
    fi
  done
}

# ── Environment ──────────────────────────────────────────────────────────────

cmd_env() {
  local service="${1:-}"
  header "Environment Variables"

  if [ -z "$service" ]; then
    info "Showing .env (secrets redacted):"
    echo ""
    if [ -f "$ROOT_DIR/.env" ]; then
      # Redact anything that looks like a secret
      sed -E 's/(PASSWORD|SECRET|TOKEN|KEY|CREDENTIALS)=.+/\1=••••••/gi' "$ROOT_DIR/.env" | grep -v "^#" | grep -v "^$"
    else
      warn "No .env file found at project root"
    fi
    echo ""
    info "Usage: $0 env <container-name>  — to show a container's runtime env"
    return
  fi

  info "Runtime environment for $service (secrets redacted):"
  echo ""
  if [ "$RUNTIME" = "container" ]; then
    container inspect "$service" 2>/dev/null | grep -i "env" || fail "Container '$service' not found"
  else
    docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$service" 2>/dev/null \
      | sed -E 's/(PASSWORD|SECRET|TOKEN|KEY|CREDENTIALS)=.+/\1=••••••/gi' \
      || fail "Container '$service' not found"
  fi
}

# ── Resource usage ───────────────────────────────────────────────────────────

cmd_resources() {
  header "Container Resource Usage"

  if [ "$RUNTIME" = "container" ]; then
    container list 2>/dev/null | grep -i "apollo" || warn "No Apollo containers running."
    info "(Apple container CLI doesn't expose stats yet — use Activity Monitor for VM memory)"
  else
    docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.NetIO}}\t{{.PIDs}}" 2>/dev/null \
      | (head -1; grep -i "apollo") \
      || warn "No Apollo containers running."
  fi
}

# ── Doctor (full diagnostic) ─────────────────────────────────────────────────

cmd_doctor() {
  echo ""
  echo -e "${BLUE}╔══════════════════════════════════════════════════════════╗${NC}"
  echo -e "${BLUE}║        Apollo Deploy — Diagnostic Report                ║${NC}"
  echo -e "${BLUE}╚══════════════════════════════════════════════════════════╝${NC}"
  echo ""
  echo -e "  Date:     $(date)"
  echo -e "  Runtime:  $RUNTIME"
  echo -e "  Arch:     $(uname -m)"
  echo -e "  OS:       $(sw_vers -productName 2>/dev/null || uname -s) $(sw_vers -productVersion 2>/dev/null || uname -r)"
  echo ""

  if [ "$RUNTIME" = "container" ]; then
    echo -e "  Container CLI: $(container --version 2>/dev/null || echo "unknown")"
  else
    echo -e "  Docker:   $(docker --version 2>/dev/null || echo "not found")"
  fi
  echo ""

  # Running containers
  header "Running Containers"
  list_containers | grep -i "apollo" || warn "No Apollo containers running."

  cmd_ports
  cmd_health
  cmd_db
  cmd_redis
  cmd_resources

  header "Summary"
  echo ""
  info "If you see failures above, common fixes:"
  echo "  • Services not listening    → make container-up (or --dev)"
  echo "  • DB not ready              → bun run db:migrate"
  echo "  • Redis auth failure        → check REDIS_PASSWORD in .env"
  echo "  • Platform unreachable      → start platform first (it's the hub)"
  echo "  • Port conflicts            → make container-down, then retry"
  echo ""
}

# ── Main ─────────────────────────────────────────────────────────────────────

main() {
  detect_runtime

  local cmd="${1:-}"
  shift 2>/dev/null || true

  case "$cmd" in
    health)     cmd_health ;;
    ports)      cmd_ports ;;
    db)         cmd_db ;;
    redis)      cmd_redis ;;
    logs)       cmd_logs "$@" ;;
    inspect)    cmd_inspect "$@" ;;
    shell)      cmd_shell "$@" ;;
    net)        cmd_net ;;
    env)        cmd_env "$@" ;;
    resources)  cmd_resources ;;
    doctor)     cmd_doctor ;;
    help|--help|-h|"")
      echo "Usage: $0 <command> [args]"
      echo ""
      echo "Debugging commands for the Apollo Deploy stack."
      echo ""
      echo "Commands:"
      echo "  health        Check all service health endpoints"
      echo "  ports         Show what's listening on expected ports"
      echo "  db            Test database connectivity and list databases"
      echo "  redis         Test Redis connectivity and show info"
      echo "  logs [svc]    Dump recent logs (all containers or specific one)"
      echo "  inspect [svc] Show container details (env, network, mounts)"
      echo "  shell [svc]   Open a shell inside a running container"
      echo "  net           Network diagnostics (DNS, routes, connectivity)"
      echo "  env [svc]     Show environment variables (secrets redacted)"
      echo "  resources     Show CPU/memory usage of containers"
      echo "  doctor        Run ALL checks and produce a full diagnostic report"
      echo ""
      echo "Examples:"
      echo "  $0 doctor                    # full diagnostic"
      echo "  $0 health                    # quick health check"
      echo "  $0 logs apollo-signal        # signal container logs"
      echo "  $0 shell apollo-platform     # shell into platform"
      echo "  $0 env apollo-billing        # show billing env vars"
      echo ""
      ;;
    *)
      fail "Unknown command: $cmd"
      echo "Run '$0 help' for usage."
      exit 1
      ;;
  esac
}

main "$@"
