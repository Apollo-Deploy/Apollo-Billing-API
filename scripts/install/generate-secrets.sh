#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Auto-generate billing-owned secrets.
# Only replaces placeholder / empty values — real values are never touched.
# ─────────────────────────────────────────────────────────────────────────────

echo "${_group}Generating secrets ..."

PLACEHOLDER_PATTERN="<generate"

_generate_if_placeholder() {
  local key="$1"
  local generator="${2:-hex}"
  local current
  current=$(get_env_value "$key")

  if [[ -z "$current" || "$current" == *"$PLACEHOLDER_PATTERN"* || "$current" == *"changeme"* ]]; then
    local value
    case "$generator" in
      secret) value=$(generate_secret 32) ;;
      *)      value=$(generate_hex_secret 32) ;;
    esac
    set_env_value "$key" "$value"
    info "Generated ${key}"
  else
    info "${key} already set — skipping."
  fi
}

# POLAR_WEBHOOK_SECRET is the only secret billing auto-generates.
# All other secrets (PLATFORM_DB_PASSWORD, BILLING_SUPERUSER_PASSWORD,
# REDIS_PASSWORD, SERVICE_AUTH_SECRET) are set by the platform installer
# and must be copied — not auto-generated.
_generate_if_placeholder POLAR_WEBHOOK_SECRET "hex"

success "Secrets ready"
echo "${_endgroup}"
