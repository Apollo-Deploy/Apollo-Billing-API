#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Run all billing database migrations in order against the platform's Postgres.
#
# Migrations run via `docker exec` into the platform postgres container —
# no host-side psql required.
# ─────────────────────────────────────────────────────────────────────────────

echo "${_group}Running database migrations ..."

DB_NAME=$(get_env_value PLATFORM_DB_NAME)
DB_USER=$(get_env_value PLATFORM_DB_USER)
DB_PASS=$(get_env_value PLATFORM_DB_PASSWORD)

[[ -z "$DB_NAME" ]] && DB_NAME="apollo_deploy_platform"
[[ -z "$DB_USER" ]] && DB_USER="billing_app"

CONTAINER="apollo-platform-postgres"

# ---------------------------------------------------------------------------
# Verify the container is running
# ---------------------------------------------------------------------------
if ! docker inspect --format='{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -q "true"; then
  error "Container '${CONTAINER}' is not running."
  error "  Start the platform stack first, then re-run install."
  exit 1
fi

info "Applying migrations in ${CONTAINER} as ${DB_USER}@${DB_NAME} ..."

# ---------------------------------------------------------------------------
# Verify connectivity
# ---------------------------------------------------------------------------
if ! docker exec -e PGPASSWORD="$DB_PASS" "$CONTAINER" \
    psql -U "$DB_USER" -d "$DB_NAME" -c "SELECT 1" -q >/dev/null 2>&1; then
  error "Cannot connect to Postgres inside ${CONTAINER} as ${DB_USER}."
  error "  Check PLATFORM_DB_USER and PLATFORM_DB_PASSWORD in .env"
  exit 1
fi

# ---------------------------------------------------------------------------
# Apply migrations
# ---------------------------------------------------------------------------
MIGRATIONS_DIR="${SCRIPT_DIR}/migrations"
MIGRATION_COUNT=0

for f in "${MIGRATIONS_DIR}"/*.psql; do
  [[ -e "$f" ]] || { warn "No migration files found in ${MIGRATIONS_DIR}"; break; }
  info "Applying $(basename "$f") ..."
  docker exec -i \
    -e PGPASSWORD="$DB_PASS" \
    "$CONTAINER" \
    psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 \
    < "$f" \
    || { error "Migration failed: $f"; exit 1; }
  MIGRATION_COUNT=$(( MIGRATION_COUNT + 1 ))
done

if [[ $MIGRATION_COUNT -eq 0 ]]; then
  warn "No migration files found in ${MIGRATIONS_DIR}"
else
  success "${MIGRATION_COUNT} migration(s) applied"
fi

echo "${_endgroup}"
