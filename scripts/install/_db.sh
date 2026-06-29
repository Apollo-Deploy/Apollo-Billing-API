#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Database / migration helpers — sourced by scripts/migrate.sh and
# scripts/install/run-migrations.sh.
#
# Connects to the shared apollo_deploy_platform Postgres as the postgres
# superuser so migrations can CREATE tables and set ownership. The billing_app
# role is used at runtime (not during schema migrations).
#
# Requires _logging.sh and _lib.sh to be sourced first.
# ─────────────────────────────────────────────────────────────────────────────

# Resolve all connection variables from .env.
db_resolve_settings() {
  DB_MIG_USER="postgres"
  # When running via docker exec the container already has POSTGRES_PASSWORD set,
  # so an empty PLATFORM_DB_ROOT_PASSWORD is fine — psql authenticates via the
  # container's own env. For host psql, set PLATFORM_DB_ROOT_PASSWORD in .env.
  DB_MIG_PASSWORD="$(env_get PLATFORM_DB_ROOT_PASSWORD)"

  DB_NAME_RESOLVED="$(env_get PLATFORM_DB_NAME)"
  DB_NAME_RESOLVED="${DB_NAME_RESOLVED:-apollo_deploy_platform}"

  local host_port host port
  host_port="$(env_get PLATFORM_DB_HOST_PORT)"
  host_port="${host_port:-127.0.0.1:${PLATFORM_DB_PORT:-5432}}"
  read -r host port <<<"$(parse_host_port "$host_port")"
  DB_HOST_RESOLVED="$host"
  DB_PORT_RESOLVED="$port"
}

# Name of the running Postgres container to exec into.
# Override via PLATFORM_DB_CONTAINER env var if your setup uses a different name.
DB_CONTAINER="${PLATFORM_DB_CONTAINER:-apollo-platform-postgres}"

# Run psql against the resolved database.
# Prefers docker exec into the platform postgres container; falls back to a
# host psql when the container isn't found (e.g. CI environments).
db_psql() {
  if docker inspect "$DB_CONTAINER" &>/dev/null; then
    docker exec -i \
      -e PGPASSWORD="$DB_MIG_PASSWORD" \
      "$DB_CONTAINER" \
      psql \
        -U "$DB_MIG_USER" -d "$DB_NAME_RESOLVED" \
        -v ON_ERROR_STOP=1 "$@"
  elif have_cmd psql; then
    PGPASSWORD="$DB_MIG_PASSWORD" psql \
      -h "$DB_HOST_RESOLVED" -p "$DB_PORT_RESOLVED" \
      -U "$DB_MIG_USER" -d "$DB_NAME_RESOLVED" \
      -v ON_ERROR_STOP=1 "$@"
  else
    log_error "Cannot run psql: container '$DB_CONTAINER' not found and no host psql available."
    return 1
  fi
}

# Returns 0 if the database accepts a connection.
# Retries up to $1 times (default 1) with a 2s pause.
db_wait_ready() {
  local attempts="${1:-1}" i=1
  while (( i <= attempts )); do
    if db_psql -c '\q' &>/dev/null; then
      return 0
    fi
    (( i < attempts )) && {
      log_detail "Postgres not ready yet (attempt ${i}/${attempts}); retrying in 2s…"
      sleep 2
    }
    i=$(( i + 1 ))
  done
  return 1
}

# Applies all *.psql files in $1 (default: scripts/migrations) in lexical order.
#
# Tracks applied migrations in a schema_migrations table. For each file:
#   • Not recorded → apply and record.
#   • Already recorded → skip.
#
# Sets DB_MIGRATIONS_APPLIED / DB_MIGRATIONS_SKIPPED on exit.
db_apply_migrations() {
  local dir="${1:-scripts/migrations}" f base already

  db_psql -c "CREATE TABLE IF NOT EXISTS schema_migrations (
      filename   TEXT PRIMARY KEY,
      applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
  );" >/dev/null

  DB_MIGRATIONS_APPLIED=0
  DB_MIGRATIONS_SKIPPED=0

  for f in $(ls "$dir"/*.psql 2>/dev/null | sort); do
    base="$(basename "$f")"
    already="$(db_psql -tAc "SELECT 1 FROM schema_migrations WHERE filename='${base}' LIMIT 1;" 2>/dev/null || true)"

    if [[ "$already" == "1" ]]; then
      log_detail "skip   ${base} (already applied)"
      DB_MIGRATIONS_SKIPPED=$(( DB_MIGRATIONS_SKIPPED + 1 ))
      continue
    fi

    log_info "apply  ${base}"
    if ! db_psql -f - < "$f"; then
      log_error "Migration failed: ${base}"
      return 1
    fi
    db_psql -c "INSERT INTO schema_migrations (filename) VALUES ('${base}') ON CONFLICT DO NOTHING;" >/dev/null
    DB_MIGRATIONS_APPLIED=$(( DB_MIGRATIONS_APPLIED + 1 ))
  done
  return 0
}
