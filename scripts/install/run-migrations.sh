#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Run all billing database migrations in order against the platform's Postgres.
# Sourced by install.sh — _logging.sh, _lib.sh, and _db.sh must already be
# sourced before this file is loaded.
# ─────────────────────────────────────────────────────────────────────────────

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
