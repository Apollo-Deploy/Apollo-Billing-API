#!/usr/bin/env bash
# Utility helpers — sourced by install.sh and standalone scripts.
#
# Conventions:
#   - bash 3.2 compatible (macOS ships an old bash; VPS installs use 4/5).
#   - All .env mutations go through env_set so quoting/escaping stay consistent.

# ── Command / environment checks ──────────────────────────────────────────────

# Aborts unless $1 is on PATH. Optional $2 is an install hint shown on failure.
require_cmd() {
    if ! command -v "$1" &>/dev/null; then
        log_error "Required command not found: ${_BOLD}$1${_RESET}"
        [[ -n "${2:-}" ]] && log_detail "$2"
        exit 1
    fi
}

# Returns 0 if a command exists (no output, no exit).
have_cmd() { command -v "$1" &>/dev/null; }

# Returns 0 if the docker compose v2 plugin is available.
has_compose_v2() { docker compose version &>/dev/null; }

# Returns 0 if the docker daemon is reachable.
docker_daemon_up() { docker info &>/dev/null; }

# Returns 0 (true) if version $1 >= version $2 (dotted numeric, e.g. 24.0.7).
# Pure-bash, so it works on macOS's bash 3.2 with no external tools. Non-numeric
# segments (e.g. a "-ce" suffix) are treated as 0 after the split.
version_gte() {
    local a="$1" b="$2" i
    local IFS=.
    # shellcheck disable=SC2206
    local va=($a) vb=($b)
    for i in 0 1 2; do
        local na="${va[$i]:-0}" nb="${vb[$i]:-0}"
        # Strip any non-digit trailers (e.g. "7-ce" → "7"); default to 0.
        na="${na%%[!0-9]*}"; nb="${nb%%[!0-9]*}"
        na="${na:-0}";       nb="${nb:-0}"
        if (( 10#$na > 10#$nb )); then return 0; fi
        if (( 10#$na < 10#$nb )); then return 1; fi
    done
    return 0  # equal
}

# Waits for a Docker container to report a healthy healthcheck status.
# Usage: wait_for_healthy <container_name> [timeout_seconds] (default 120).
# Returns 1 on timeout or if the container has no healthcheck/doesn't exist.
wait_for_healthy() {
    local container="$1" timeout="${2:-120}" elapsed=0 status
    while true; do
        status=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
            "$container" 2>/dev/null || echo "missing")
        case "$status" in
            healthy) return 0 ;;
            none)    return 1 ;;  # container has no healthcheck — caller should fall back
        esac
        if (( elapsed >= timeout )); then
            log_warn "${container} did not become healthy within ${timeout}s (last status: ${status})."
            return 1
        fi
        sleep 3
        elapsed=$(( elapsed + 3 ))
    done
}

# ── .env read/write ────────────────────────────────────────────────────────────

# Reads a key from .env; prints its value (quotes stripped) or empty string.
env_get() {
    grep -E "^${1}=" .env 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"' || true
}

# Writes (or updates) key=value in .env. Values are written verbatim; callers
# that need spaces/specials in a value should quote it themselves.
env_set() {
    local key="$1" value="$2"
    if [[ ! -f .env ]]; then touch .env; fi
    if grep -qE "^${key}=" .env 2>/dev/null; then
        # Use a temp file rather than sed -i to stay portable across GNU/BSD sed
        # and to avoid issues when the value contains sed metacharacters.
        local tmp
        tmp="$(mktemp)"
        awk -v k="$key" -v v="$value" \
            'BEGIN{FS=OFS="="} $1==k {print k"="v; next} {print}' .env > "$tmp"
        mv "$tmp" .env
    else
        echo "${key}=${value}" >> .env
    fi
}

# True if a key is unset, empty, or still set to a placeholder ("changeme").
env_needs_value() {
    local val
    val="$(env_get "$1")"
    [[ -z "$val" || "$val" == "changeme" ]]
}

# ── Secret generation ──────────────────────────────────────────────────────────

# Prints a 64-char hex secret (32 random bytes).
gen_secret() {
    if have_cmd openssl; then
        openssl rand -hex 32
    else
        head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'
    fi
}

# Prints a URL-safe base64 secret of N bytes (default 32).
gen_secret_b64() {
    local bytes="${1:-32}"
    if have_cmd openssl; then
        openssl rand -base64 "$bytes" | tr '+/' '-_' | tr -d '=\n'
    else
        head -c "$bytes" /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n'
    fi
}

# ── Networking helpers ─────────────────────────────────────────────────────────

# Splits "host:port" or "ip:host:port" style values into host/port.
# Usage: parse_host_port "127.0.0.1:5432"  → echoes "127.0.0.1 5432"
parse_host_port() {
    local val="$1" host port
    port="${val##*:}"
    host="${val%:*}"
    [[ "$host" == "$port" || -z "$host" ]] && host="127.0.0.1"
    echo "$host $port"
}

# Returns 0 if a TCP port appears to be in use on localhost.
# Tries lsof, then nc, then /dev/tcp; silently returns 1 if none are available.
port_in_use() {
    local port="$1"
    if have_cmd lsof; then
        lsof -nP -iTCP:"$port" -sTCP:LISTEN &>/dev/null && return 0 || return 1
    elif have_cmd nc; then
        nc -z 127.0.0.1 "$port" &>/dev/null && return 0 || return 1
    elif (: < "/dev/tcp/127.0.0.1/$port") &>/dev/null; then
        return 0
    fi
    return 1
}

# ── Interactive prompt ─────────────────────────────────────────────────────────

# Yes/no prompt. Usage: confirm "Question?" [default:Y|N]  → returns 0 for yes.
# Auto-answers with the default in non-interactive mode.
confirm() {
    local prompt="$1" default="${2:-N}" reply
    if [[ "${INTERACTIVE:-1}" -eq 0 ]]; then
        [[ "$default" == "Y" ]] && return 0 || return 1
    fi
    local hint="[y/N]"
    [[ "$default" == "Y" ]] && hint="[Y/n]"
    read -r -p "${_BOLD}${prompt}${_RESET} ${hint} " reply
    reply="${reply:-$default}"
    [[ "$reply" =~ ^[Yy] ]]
}
