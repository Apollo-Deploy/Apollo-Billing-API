#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Interactive configuration — sourced by ensure-env.sh.
#
# On first run:  prompts for all platform secrets + Polar + app settings.
# On re-runs:    only prompts for keys that are still missing in .env.
#
# Arguments: zero or more "KEY|hint" entries (passed by ensure-env.sh).
#   If no arguments are given, all sections are shown (first-run path).
#
# Uses only bash 3.2-compatible syntax (macOS default shell).
# ─────────────────────────────────────────────────────────────────────────────

echo "${_group}Interactive setup ..."

# Build a lookup of which keys need prompting.
# Stored as a space-separated string of keys (bash 3.2-compatible, no declare -A)
_need_keys=""
_first_run=0

if [[ $# -eq 0 ]]; then
  _first_run=1
else
  for _entry in "$@"; do
    _k="${_entry%%|*}"
    _need_keys="${_need_keys} ${_k} "
  done
fi

# Returns 0 (true) if KEY is in the _need_keys list
_need_has() {
  case " ${_need_keys} " in
    *" $1 "*) return 0 ;;
    *) return 1 ;;
  esac
}

# ---------------------------------------------------------------------------
# Helper: prompt for a value
# Usage: _prompt <KEY> <label> <default> [secret=0]
#   Skips silently if the key is not in _need (re-run mode).
# ---------------------------------------------------------------------------
_prompt() {
  local key="$1" label="$2" default="$3" secret="${4:-0}"

  # Re-run mode: skip keys that aren't missing
  if [[ $_first_run -eq 0 ]] && ! _need_has "$key"; then
    return 0
  fi

  local disp_default=""
  [[ -n "$default" ]] && disp_default=" [${default}]"

  local val
  if [[ "$secret" -eq 1 ]]; then
    read -r -s -p "  ${label}${disp_default}: " val
    echo ""
  else
    read -r -p "  ${label}${disp_default}: " val
  fi

  [[ -z "$val" ]] && val="$default"
  set_env_value "$key" "$val"

  # Zeroize from memory where possible
  val=""
}

# ---------------------------------------------------------------------------
# Helper: print a section header only if it contains at least one missing key
# ---------------------------------------------------------------------------
_section_needed() {
  if [[ $_first_run -eq 1 ]]; then return 0; fi
  for _k in "$@"; do
    _need_has "$_k" && return 0
  done
  return 1
}

# ---------------------------------------------------------------------------
# Platform-provided credentials
# ---------------------------------------------------------------------------
if _section_needed \
    PLATFORM_DB_PASSWORD \
    BILLING_SUPERUSER_PASSWORD \
    REDIS_PASSWORD \
    SERVICE_AUTH_SECRET; then

  echo ""
  echo "  Platform credentials  (from the platform installer output)"
  echo "  ─────────────────────────────────────────────────────────"

  _prompt PLATFORM_DB_PASSWORD \
    "PLATFORM_DB_PASSWORD       (platform: BILLING_APP_DB_PASS)" \
    "" 1

  _prompt BILLING_SUPERUSER_PASSWORD \
    "BILLING_SUPERUSER_PASSWORD (platform: BILLING_SUPERUSER_DB_PASS)" \
    "" 1

  _prompt REDIS_PASSWORD \
    "REDIS_PASSWORD             (platform: REDIS_PASSWORD)" \
    "" 1

  _prompt SERVICE_AUTH_SECRET \
    "SERVICE_AUTH_SECRET        (platform: INTERNAL_SERVICE_SECRET)" \
    "" 1
fi

# ---------------------------------------------------------------------------
# Polar  (only shown on first run — Polar keys aren't platform-provided)
# ---------------------------------------------------------------------------
if [[ $_first_run -eq 1 ]]; then
  echo ""
  echo "  Polar"
  echo "  ─────"

  _prompt POLAR_API_KEY \
    "Polar API key" \
    "" 1

  # Read webhook secret separately — blank triggers auto-generate
  local _webhook_val=""
  read -r -s -p "  Polar webhook secret  (blank = auto-generate): " _webhook_val
  echo ""
  if [[ -z "$_webhook_val" ]]; then
    _webhook_val="$(generate_hex_secret 32)"
    info "Auto-generated POLAR_WEBHOOK_SECRET"
  fi
  set_env_value POLAR_WEBHOOK_SECRET "$_webhook_val"
  _webhook_val=""
fi

# ---------------------------------------------------------------------------
# Application  (only shown on first run)
# ---------------------------------------------------------------------------
if [[ $_first_run -eq 1 ]]; then
  echo ""
  echo "  Application"
  echo "  ───────────"

  _prompt BILLING_BIND \
    "HTTPS bind address" \
    "0.0.0.0:443"

  _prompt PLATFORM_NETWORK \
    "Platform Docker network name" \
    "platform_default"
fi

echo ""
success "Configuration written to .env"

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------
unset _need_keys _first_run _k _h _entry _webhook_val

echo "${_endgroup}"
