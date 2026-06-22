#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Shared utilities — env loading, secret generation, Docker helpers.
# ─────────────────────────────────────────────────────────────────────────────

# ---------------------------------------------------------------------------
# Env loading
# Allow a .env.custom file to override .env without modifying .env itself.
# ---------------------------------------------------------------------------
if [[ -f .env.custom ]]; then
  _ENV=.env.custom
else
  _ENV=.env
fi

if [[ "$_ENV" == ".env.custom" ]]; then
  q=$(mktemp) && export -p >"$q" && set -a && . ".env.custom" && set +a && . "$q" && rm "$q" && unset q
fi

if [[ -f .env ]]; then
  t=$(mktemp) && export -p >"$t" && set -a && . ".env" && set +a && . "$t" && rm "$t" && unset t
fi

# ---------------------------------------------------------------------------
# .env read / write helpers
# ---------------------------------------------------------------------------

# Read a value from .env (returns empty string if not set or file missing)
get_env_value() {
  local key="$1"
  grep "^${key}=" .env 2>/dev/null \
    | cut -d'=' -f2- \
    | tr -d '"' \
    | tr -d "'" \
    | sed 's/[[:space:]]*#.*//' \
    | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' \
    || true
}

# Write or update a key=value pair in .env
set_env_value() {
  local key="$1"
  local value="$2"
  local escaped
  escaped=$(printf '%s\n' "$value" | sed -e 's/[\/&]/\\&/g')
  if grep -q "^${key}=" .env 2>/dev/null; then
    sed -i.bak "s|^${key}=.*|${key}=${escaped}|" .env && rm -f .env.bak
  else
    echo "${key}=${escaped}" >>.env
  fi
}

# ---------------------------------------------------------------------------
# Secret generation
# ---------------------------------------------------------------------------

# URL-safe base64 secret of $1 bytes (default 32)
generate_secret() {
  local bytes="${1:-32}"
  openssl rand -base64 "$bytes" | tr -d '\n/+=' | head -c "$((bytes * 4 / 3))"
}

# Hex secret of $1 bytes (default 32)
generate_hex_secret() {
  local bytes="${1:-32}"
  openssl rand -hex "$bytes"
}

# ---------------------------------------------------------------------------
# Docker / Compose helpers
# ---------------------------------------------------------------------------

# Detect whether to use "docker compose" (v2) or "docker-compose" (v1).
# Exports DC_CMD so all sub-scripts can reuse it.
detect_compose_cmd() {
  if docker compose version &>/dev/null 2>&1; then
    echo "docker compose"
  elif command -v docker-compose &>/dev/null; then
    echo "docker-compose"
  else
    error "Neither 'docker compose' (v2) nor 'docker-compose' (v1) found."
    exit 1
  fi
}

# Wait for a container to report healthy.
# Usage: wait_for_healthy <container_name> [timeout_seconds]
wait_for_healthy() {
  local container="$1"
  local timeout="${2:-120}"
  local elapsed=0
  info "Waiting for ${container} to become healthy (timeout: ${timeout}s) ..."
  while true; do
    local status
    status=$(docker inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null || echo "missing")
    if [[ "$status" == "healthy" ]]; then
      success "${container} is healthy."
      return 0
    fi
    if [[ "$elapsed" -ge "$timeout" ]]; then
      error "${container} did not become healthy within ${timeout}s (last status: ${status})."
      return 1
    fi
    sleep 3
    elapsed=$((elapsed + 3))
  done
}

# ---------------------------------------------------------------------------
# Error trap
# ---------------------------------------------------------------------------

# Courtesy of https://stackoverflow.com/a/2183063/90297
trap_with_arg() {
  local func="$1"; shift
  for sig; do
    # shellcheck disable=SC2064
    trap "$func $sig" "$sig"
  done
}

DID_CLEAN_UP=0
cleanup() {
  local retcode=$?
  if [[ "$DID_CLEAN_UP" -eq 1 ]]; then return 0; fi
  DID_CLEAN_UP=1

  if [[ "$1" != "EXIT" ]]; then
    set +o xtrace
    local failed_cmd="${BASH_COMMAND}"
    error "Installation failed (exit ${retcode}) while running: ${failed_cmd}"
    error "Check the log file for details: ${log_file:-apollo_billing_install_log.txt}"

    local depth=${#FUNCNAME[@]}
    if [[ $depth -gt 2 ]]; then
      echo ""
      echo "  Stack trace:"
      for ((i = 1; i < depth; i++)); do
        printf "    %s:%s in %s()\n" \
          "${BASH_SOURCE[$i]:-?}" \
          "${BASH_LINENO[$i - 1]:-?}" \
          "${FUNCNAME[$i]:-?}"
      done
      echo ""
    fi
  fi
}
