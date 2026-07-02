#!/usr/bin/env bash
# Apply all SQL migrations in scripts/migrations/ in lexical order.
#
# Standalone entrypoint for `make migrate`. Connection settings are read from
# .env (PLATFORM_DB_NAME, PLATFORM_DB_ROOT_PASSWORD, PLATFORM_DB_HOST_PORT).
# Runs migrations as the postgres superuser via docker exec into the platform
# postgres container (or a host psql when the container isn't found).
#
# Usage:
#   ./scripts/migrate.sh
#   make migrate

set -eEuo pipefail
test "${DEBUG:-}" && set -x

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$ROOT_DIR"

# shellcheck source=scripts/install/_logging.sh
source scripts/install/_logging.sh

# shellcheck source=scripts/install/_lib.sh
source scripts/install/_lib.sh

# shellcheck source=scripts/install/_db.sh
source scripts/install/_db.sh

if [[ ! -f .env ]]; then
  log_error "No .env found — run ./setup.sh --service billing from repo root (or copy .env.example to .env)."
  exit 1
fi

log_step "Database migrations"

db_resolve_settings
log_detail "Connecting as ${DB_MIG_USER}@${DB_NAME_RESOLVED} via container ${DB_CONTAINER}"

if ! db_wait_ready 1; then
  log_error "Cannot reach Postgres (container: ${DB_CONTAINER})."
  log_detail "Make sure the platform stack is running: docker compose up -d"
  exit 1
fi

db_apply_migrations "scripts/migrations" || exit 1

if [[ "$DB_MIGRATIONS_APPLIED" -eq 0 && "$DB_MIGRATIONS_SKIPPED" -eq 0 ]]; then
  log_warn "No migration files found in scripts/migrations/"
else
  log_success "Migrations complete — ${DB_MIGRATIONS_APPLIED} applied, ${DB_MIGRATIONS_SKIPPED} already present"
fi
