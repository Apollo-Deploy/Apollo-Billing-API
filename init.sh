#!/usr/bin/env bash
# =============================================================================
# init.sh — One-time billing setup (run once, then just `docker compose up -d`)
#
# What this does (things compose can't handle):
#   1. Create .env from .env.example if missing
#   2. Run database migrations (billing tables in the platform DB)
#
# Prerequisites:
#   • Platform stack running (postgres healthy)
#   • Billing .env populated with platform-provided values
#     (PLATFORM_DB_PASSWORD, BILLING_SUPERUSER_PASSWORD, REDIS_PASSWORD)
#
# After init.sh completes: `docker compose up -d` is all you need.
#
# Usage:
#   ./init.sh                    # full first-time setup
#   ./init.sh --skip-migrations  # skip DB migrations
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ── Flags ──────────────────────────────────────────────────────────────────────
SKIP_MIGRATIONS=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-migrations) SKIP_MIGRATIONS=true; shift ;;
    -h|--help) sed -n '2,14p' "$0" | grep '^#' | sed 's/^# \?//'; exit 0 ;;
    *) echo "Unknown: $1"; exit 1 ;;
  esac
done

# ── Helpers ────────────────────────────────────────────────────────────────────
info()  { printf '  \033[0;32m✔\033[0m  %s\n' "$*"; }
warn()  { printf '  \033[1;33m⚠\033[0m  %s\n' "$*"; }
error() { printf '  \033[0;31m✖\033[0m  %s\n' "$*" >&2; }
step()  { printf '\n\033[1;36m▶  %s\033[0m\n' "$*"; }

# ── 1. Ensure .env ─────────────────────────────────────────────────────────────
step "Environment"
if [[ ! -f .env ]]; then
  if [[ -f .env.example ]]; then
    cp .env.example .env
    info "Created .env from .env.example — fill in required values."
    warn "Required: PLATFORM_DB_PASSWORD, BILLING_SUPERUSER_PASSWORD, REDIS_PASSWORD"
  else
    error ".env.example not found."; exit 1
  fi
else
  info ".env exists."
fi

# ── 2. Migrations ──────────────────────────────────────────────────────────────
if $SKIP_MIGRATIONS; then
  warn "Skipping migrations (--skip-migrations)"
else
  step "Migrations"
  if [[ -f scripts/migrate.sh ]]; then
    bash scripts/migrate.sh
  elif [[ -f ../scripts/setup/migrate-all-remote.sh ]]; then
    ../scripts/setup/migrate-all-remote.sh --only billing
  else
    warn "No migration script found — skipping."
  fi
fi

# ── Done ───────────────────────────────────────────────────────────────────────
step "Done"
printf '\n'
info "Billing initialized. Start with:"
printf '     docker compose up -d\n\n'
